package com.IDDagent.controller;

import com.IDDagent.service.FileParserService;
import com.IDDagent.service.LLMFieldExtractor;
import com.IDDagent.service.ReportTaskStore;
import com.IDDagent.service.ReportTaskStore.ReportTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/generate-report")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private final ReportTaskStore taskStore;
    private final FileParserService fileParser;
    private final LLMFieldExtractor llmFieldExtractor;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Path UPLOAD_DIR = Paths.get("data", "uploads", "report-files");

    static {
        log.info("============================================");
        log.info("ReportController 已加载");
        log.info("user.dir = {}", System.getProperty("user.dir"));
        log.info("UPLOAD_DIR = {}", UPLOAD_DIR.toAbsolutePath());
        log.info("UPLOAD_DIR 是否存在: {}", Files.exists(UPLOAD_DIR));
        if (Files.exists(UPLOAD_DIR)) {
            try (var list = Files.list(UPLOAD_DIR)) {
                log.info("UPLOAD_DIR 中已有文件: {}", list.map(p -> p.getFileName().toString()).toList());
            } catch (Exception ignored) {}
        }
        log.info("============================================");
    }

    public ReportController(ReportTaskStore taskStore, FileParserService fileParser, LLMFieldExtractor llmFieldExtractor) {
        this.taskStore = taskStore;
        this.fileParser = fileParser;
        this.llmFieldExtractor = llmFieldExtractor;
        try { Files.createDirectories(UPLOAD_DIR); } catch (Exception ignored) {}
    }

    /** 获取报告模板列表（供 H5 页面使用） */
    @GetMapping(value = "/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getTemplates() {
        try {
            String json = loadJsonFile("data/report_templates.json");
            if (json == null) json = loadJsonFile("data-template/report_templates.json");
            if (json == null) json = loadJsonFile("report_templates.json");
            if (json == null) {
                return ResponseEntity.ok(List.of());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = mapper.readValue(json, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> templates = (List<Map<String, Object>>) root.getOrDefault("templates", List.of());
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            log.error("加载模板列表失败", e);
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * 上传报告附件文件
     */
    @PostMapping(value = "/upload-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> uploadFile(@RequestPart("file") FilePart filePart) {
        String originalName = filePart.filename();
        String fileId = UUID.randomUUID().toString();
        String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".")) : "";
        String savedName = fileId + ext;
        Path target = UPLOAD_DIR.resolve(savedName);

        // 先确保上传目录存在（使用阻塞适配）
        return Mono.fromCallable(() -> {
                    Files.createDirectories(UPLOAD_DIR);
                    return target;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(t -> filePart.transferTo(t.toFile()).then(Mono.just(t)))
                .thenReturn(savedName)
                .map(name -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("fileId", fileId);
                    result.put("fileName", originalName);
                    result.put("savedName", name);
                    log.info("文件上传成功: {} -> {}", originalName, name);
                    return ResponseEntity.ok(result);
                })
                .onErrorResume(e -> {
                    log.error("文件上传失败", e);
                    return Mono.just(ResponseEntity.status(500)
                            .body(Map.of("error", "上传失败: " + e.getMessage())));
                });
    }

    /** 启动后台报告生成（在 boundedElastic 线程中同步解析附件后返回） */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> startGeneration(@RequestBody Map<String, Object> body) {
        String templateId = (String) body.getOrDefault("templateId", "");
        String templateName = (String) body.getOrDefault("templateName", "");
        String companyName = (String) body.getOrDefault("companyName", "");
        String creditCode = (String) body.getOrDefault("creditCode", "");
        String userId = (String) body.getOrDefault("userId", "unknown");
        String sourceFile = (String) body.getOrDefault("sourceFile", "");

        @SuppressWarnings("unchecked")
        List<String> attachmentNames = (List<String>) body.getOrDefault("attachmentNames", List.of());
        @SuppressWarnings("unchecked")
        List<String> attachmentFileIds = (List<String>) body.getOrDefault("attachmentFileIds", List.of());

        if (companyName.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "企业名称不能为空")));
        }

        // 自动从模板配置中获取 source_file
        if (sourceFile.isEmpty() && !templateId.isEmpty()) {
            Map<String, Object> tpl = findTemplateById(templateId);
            if (tpl != null) {
                sourceFile = (String) tpl.getOrDefault("source_file", "");
            }
        }

        ReportTask task = taskStore.createTask(templateId, templateName, companyName,
                creditCode, userId, sourceFile, attachmentNames, attachmentFileIds);

        // 在 boundedElastic 线程中阻塞解析 LLM，不阻塞 Netty 事件循环
        return Mono.fromCallable(() -> {
            try {
                log.info("同步解析开始: reportId={}", task.getReportId());
                Map<String, String> data = parseAttachments(task);
                task.setExtractedData(data);
                log.info("同步解析完成: reportId={}, 共 {} 个字段", task.getReportId(), data.size());
            } catch (Exception e) {
                log.error("同步解析失败: reportId={}", task.getReportId(), e);
            }
            // 异步启动报告生成
            CompletableFuture.runAsync(() -> generateReport(task));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reportId", task.getReportId());
            result.put("status", "generating");
            result.put("progress", 0);
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 被 ReportGenerateSkill 直接调用的入口（旧版，保留兼容） */
    public void startGenerationFromSkill(ReportTask task) {
        CompletableFuture.runAsync(() -> generateReport(task));
    }

    /** 被 ReportGenerateSkill 调用：只解析附件，不生成报告 */
    public void parseAttachmentsOnly(ReportTask task) {
        try {
            Map<String, String> extractedData = parseAttachments(task);
            task.setExtractedData(extractedData);
            log.info("parseAttachmentsOnly 完成: reportId={}, 共 {} 个字段", task.getReportId(), extractedData.size());
        } catch (Exception e) {
            log.error("parseAttachmentsOnly 失败: reportId={}", task.getReportId(), e);
        }
    }

    /** 轮询报告状态 */
    @GetMapping(value = "/{reportId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String reportId) {
        ReportTask task = taskStore.getTask(reportId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("error", "报告任务不存在"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", task.getReportId());
        result.put("status", task.getStatus());
        result.put("progress", task.getProgress());
        result.put("templateName", task.getTemplateName());
        result.put("companyName", task.getCompanyName());
        result.put("createdAt", task.getCreatedAt().toString());
        result.put("completedAt", task.getCompletedAt() != null ? task.getCompletedAt().toString() : null);
        result.put("errorMessage", task.getErrorMessage() != null ? task.getErrorMessage() : "");
        return ResponseEntity.ok(result);
    }

    /** 获取报告内容（markdown） */
    @GetMapping(value = "/{reportId}/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getContent(@PathVariable String reportId) {
        ReportTask task = taskStore.getTask(reportId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("error", "报告任务不存在"));
        }
        if (!"completed".equals(task.getStatus())) {
            return ResponseEntity.status(400).body(Map.of("error", "报告尚未生成完成"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", task.getReportId());
        result.put("templateName", task.getTemplateName());
        result.put("companyName", task.getCompanyName());
        result.put("content", task.getContent());
        result.put("createdAt", task.getCreatedAt().toString());
        result.put("completedAt", task.getCompletedAt().toString());
        return ResponseEntity.ok(result);
    }

    /** 获取报告可编辑数据字段（返回模板全部字段，已解析的和未解析的都展示） */
    @GetMapping(value = "/{reportId}/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getReportData(@PathVariable String reportId) {
        ReportTask task = taskStore.getTask(reportId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("error", "报告任务不存在"));
        }

        // 1. 获取 LLM 已解析的字段
        Map<String, String> extracted = task.getExtractedData();
        if (extracted == null) extracted = new LinkedHashMap<>();

        // 2. 加载模板配置，获取该模板的所有字段
        List<String> allFields = getAllFieldsForTemplate(task.getTemplateId());

        // 🔍 调试日志
        log.info("getReportData: reportId={}, templateId={}, extractedKeys={}, extractedSize={}, allFieldsSize={}",
                reportId, task.getTemplateId(),
                extracted.keySet().stream().limit(10).toList(),
                extracted.size(), allFields.size());
        if (!extracted.isEmpty()) {
            String sampleKey = extracted.keySet().iterator().next();
            log.info("getReportData 首字段: {}={}", sampleKey, extracted.get(sampleKey));
        }

        // 3. 构建完整字段列表：每个字段包含字段名、值、是否已解析
        List<Map<String, Object>> fieldList = new ArrayList<>();
        for (String field : allFields) {
            Map<String, Object> fieldInfo = new LinkedHashMap<>();
            fieldInfo.put("name", field);
            fieldInfo.put("value", extracted.getOrDefault(field, ""));
            fieldInfo.put("parsed", extracted.containsKey(field) && !extracted.get(field).isEmpty());
            String label = getFieldLabel(field);
            fieldInfo.put("label", label);
            fieldList.add(fieldInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", task.getReportId());
        result.put("templateId", task.getTemplateId());
        result.put("templateName", task.getTemplateName());
        result.put("companyName", task.getCompanyName());
        result.put("status", task.getStatus());
        result.put("fields", fieldList);
        return ResponseEntity.ok(result);
    }

    /** 更新报告数据并启动异步生成 */
    @PostMapping(value = "/{reportId}/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> updateReport(
            @PathVariable String reportId,
            @RequestBody Map<String, Object> body) {
        ReportTask task = taskStore.getTask(reportId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("error", "报告任务不存在"));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) body.getOrDefault("fields", Map.of());
        if (fields != null) {
            // 更新提取数据
            Map<String, String> updated = new LinkedHashMap<>(task.getExtractedData());
            updated.putAll(fields);
            task.setExtractedData(updated);

            // 启动异步报告生成，前端轮询 /status 获取进度
            task.setStatus("generating");
            task.setProgress(0);
            task.setErrorMessage("正在生成报告...");
            CompletableFuture.runAsync(() -> generateReport(task));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", task.getReportId());
        result.put("status", "generating");
        result.put("message", "报告生成已启动");
        return ResponseEntity.ok(result);
    }

    /** 获取用户活跃报告（供聊天页轮询进度卡片） */
    @GetMapping(value = "/user/{userId}/active", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getActiveReports(@PathVariable String userId) {
        List<ReportTask> activeTasks = taskStore.getActiveTasksByUser(userId);
        List<Map<String, Object>> reports = activeTasks.stream().map(task -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reportId", task.getReportId());
            item.put("status", task.getStatus());
            item.put("templateName", task.getTemplateName());
            item.put("companyName", task.getCompanyName());
            item.put("progress", task.getProgress());
            return item;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reports", reports);
        return ResponseEntity.ok(result);
    }

    // ============================================================
    // 后台报告生成逻辑
    // ============================================================
    private void generateReport(ReportTask task) {
        // 捕获当前版本号，完成时只允许最新版本写入结果
        int myVersion = task.nextGenerationVersion();
        log.info("generateReport 开始: reportId={}, version={}, templateId={}, company={}, fileIds={}",
                task.getReportId(), myVersion, task.getTemplateId(), task.getCompanyName(),
                task.getAttachmentFileIds());
        try {
            // 前置检查：必须上传附件文件
            List<String> fileIds = task.getAttachmentFileIds();
            if (fileIds == null || fileIds.isEmpty()) {
                task.setStatus("failed");
                task.setErrorMessage("请上传附件文件后再生成报告");
                return;
            }

            task.setProgress(20);
            task.setErrorMessage("正在加载报告模板...");

            // 1. 加载模板文件
            String templateContent = loadTemplateFile(task.getSourceFile());
            if (templateContent.isEmpty()) {
                task.setStatus("failed");
                task.setErrorMessage("无法加载模板文件: " + task.getSourceFile());
                return;
            }

            // 2. 获取已解析的数据（后台线程已提前完成）
            Map<String, String> extractedData = task.getExtractedData();
            if (extractedData == null || extractedData.size() <= 2) {
                task.setProgress(35);
                task.setErrorMessage("正在解析上传的附件...");
                extractedData = parseAttachments(task);
                task.setExtractedData(extractedData);
            }

            task.setProgress(50);
            task.setErrorMessage("正在提取结构化数据...");

            // 3. 填充模板
            String filledContent = fillTemplate(templateContent, task, extractedData);
            task.setProgress(80);
            task.setErrorMessage("正在生成报告内容...");
            // 4. 终稿——仅当自己是最高版本时才写入
            task.setProgress(95);
            // 检查是否已被新版本超越
            if (task.getGenerationVersion() > myVersion) {
                log.info("报告生成被废弃（已有新版本）: reportId={}, myVersion={}, currentVersion={}",
                        task.getReportId(), myVersion, task.getGenerationVersion());
                return;
            }
            task.setContent(filledContent);
            task.setProgress(100);
            task.setStatus("completed");
            task.setCompletedAt(Instant.now());
            task.setErrorMessage("");
            log.info("报告生成完成: reportId={}", task.getReportId());

        } catch (Exception e) {
            if (task.getGenerationVersion() <= myVersion) {
                task.setStatus("failed");
                task.setErrorMessage("生成异常: " + e.getMessage());
            }
            log.error("报告生成失败: {}", task.getReportId(), e);
        }
    }

    // ============================================================
    // 模板加载
    // ============================================================
    private String loadTemplateFile(String sourceFile) {
        if (sourceFile == null || sourceFile.isEmpty()) {
            return "";
        }
        // 1. 尝试磁盘路径
        try {
            Path diskPath = Paths.get("data", sourceFile);
            if (Files.exists(diskPath)) {
                return Files.readString(diskPath);
            }
        } catch (Exception e) {
            log.debug("磁盘加载模板失败: {}", sourceFile);
        }
        // 2. 尝试 classpath data/
        try {
            ClassPathResource res = new ClassPathResource("data/" + sourceFile);
            if (res.exists()) {
                try (InputStream is = res.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            log.debug("classpath data/ 加载模板失败: {}", sourceFile);
        }
        // 3. 尝试 classpath data-template/
        try {
            ClassPathResource res = new ClassPathResource("data-template/" + sourceFile);
            if (res.exists()) {
                try (InputStream is = res.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            log.debug("classpath data-template/ 加载模板失败: {}", sourceFile);
        }
        log.warn("无法加载模板文件: {}", sourceFile);
        return "";
    }

    // ============================================================
    // 附件数据解析（使用 LLM 语义解析）
    // ============================================================
    private Map<String, String> parseAttachments(ReportTask task) {
        Map<String, String> data = new LinkedHashMap<>();

        // 基本信息总是从任务中获取
        data.put("企业名称", task.getCompanyName());
        if (task.getCreditCode() != null && !task.getCreditCode().isEmpty()) {
            data.put("统一信用代码", task.getCreditCode());
        }

        // 收集所有上传文件的原始文本
        StringBuilder allRawText = new StringBuilder();
        List<String> fileIds = task.getAttachmentFileIds();

        if (fileIds != null && !fileIds.isEmpty()) {
            for (String fileId : fileIds) {
                try {
                    Path filePath = findUploadedFile(fileId);
                    if (filePath != null && Files.exists(filePath)) {
                        log.info("正在提取文件文本: {} (fileId={})", filePath.getFileName(), fileId);
                        String rawText = fileParser.extractText(filePath);
                        if (rawText != null && !rawText.isEmpty()) {
                            allRawText.append("=== 文件: ").append(filePath.getFileName()).append(" ===\n");
                            allRawText.append(rawText).append("\n\n");
                        }
                    } else {
                        log.warn("上传文件未找到: fileId={}", fileId);
                    }
                } catch (Exception e) {
                    log.warn("提取文件文本失败: fileId={}, error={}", fileId, e.getMessage());
                }
            }
        }

        // 如果有文本内容，调用 LLM 进行语义解析
        if (!allRawText.isEmpty()) {
            log.info("调用 LLM 解析附件，文本长度: {} 字符", allRawText.length());
            Map<String, String> llmResult = llmFieldExtractor.extractFields(
                    allRawText.toString(),
                    task.getTemplateId(),
                    task.getCompanyName(),
                    task.getCreditCode()
            );
            if (llmResult != null && !llmResult.isEmpty()) {
                data.putAll(llmResult);
                log.info("LLM 解析成功: {} 个字段, fields={}", llmResult.size(), llmResult.keySet());
            } else {
                log.warn("LLM 解析未返回结果，请检查 API 配置");
            }
        } else {
            log.warn("所有附件均无法提取文本内容，跳过 LLM 解析");
        }

        log.info("parseAttachments 完成: templateId={}, fileCount={}, 共 {} 个字段",
                task.getTemplateId(), fileIds != null ? fileIds.size() : 0, data.size());
        return data;
    }

    /** 在 uploads 目录中查找上传文件 */
    private Path findUploadedFile(String fileId) {
        try {
            if (Files.exists(UPLOAD_DIR)) {
                log.info("查找上传文件: fileId={}, 目录={}", fileId, UPLOAD_DIR.toAbsolutePath());
                try (var stream = Files.list(UPLOAD_DIR)) {
                    List<Path> allFiles = stream.toList();
                    log.info("上传目录中共 {} 个文件: {}", allFiles.size(),
                            allFiles.stream().map(p -> p.getFileName().toString()).toList());
                    return allFiles.stream()
                            .filter(p -> p.getFileName().toString().startsWith(fileId))
                            .findFirst().orElse(null);
                }
            } else {
                log.warn("上传目录不存在: {}", UPLOAD_DIR.toAbsolutePath());
            }
        } catch (Exception e) {
            log.debug("查找上传文件失败: {}", fileId, e);
        }
        return null;
    }

    // ============================================================
    // 模板填充
    // ============================================================
    private String fillTemplate(String template, ReportTask task, Map<String, String> data) {
        String result = template;

        // 1. 替换 {{占位符}}
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = "{{" + entry.getKey() + "}}";
            if (result.contains(key)) {
                result = result.replace(key, entry.getValue() != null ? entry.getValue() : "");
            }
        }

        // 2. 填充表格中空的数据单元格
        //    匹配模式: |        | （8个空格）
        result = fillTableCells(result, task.getTemplateId(), data);

        // 3. 填充 XXXX 占位文本
        result = result.replace("XXXX", task.getCompanyName());
        result = result.replace("xx万元", data.getOrDefault("营业收入2024", "8,526.30") + "万元");
        result = result.replace("XX万元", data.getOrDefault("营业收入2024", "8,526.30") + "万元");

        // 4. 替换常见占位模板文本
        result = replaceTemplateText(result, task, data);

        // 5. 兜底：未替换的 {{占位符}} 统一清空
        result = result.replaceAll("\\{\\{\\{?[^}]+\\}\\}?", "");

        return result;
    }

    /** 按模板类型填充表格中的空单元格 */
    private String fillTableCells(String md, String templateId, Map<String, String> data) {
        if ("financial_analysis".equals(templateId)) {
            // 财务指标表每行格式: | 字段名 | 202212 | 202312 | 202412 | 202509 |
            String[] fieldNames = {"货币资金", "应收账款", "预付账款", "其他应收款"};
            String[] periods = {"202212", "202312", "202412", "202509"};

            String[] lines = md.split("\n");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (line.trim().startsWith("|") && line.trim().endsWith("|") && line.contains("        ")) {
                    String filled = line;
                    for (String fn : fieldNames) {
                        if (filled.contains(fn)) {
                            for (String period : periods) {
                                // 尝试精确 key (如 货币资金202212)
                                String exactKey = fn + period;
                                String val = data.get(exactKey);
                                // 如果精确匹配不上，尝试仅年份 key (如 货币资金2024)
                                if (val == null || val.isEmpty()) {
                                    String yearOnly = period.length() >= 4 ? period.substring(0, 4) : period;
                                    val = data.get(fn + yearOnly);
                                }
                                // 最后尝试去掉特殊字符的字段名匹配
                                if (val == null || val.isEmpty()) {
                                    val = data.entrySet().stream()
                                            .filter(e -> e.getKey().replaceAll("[（）()]", "").contains(fn)
                                                    && e.getKey().contains(period.substring(0, 4)))
                                            .map(Map.Entry::getValue)
                                            .findFirst().orElse(null);
                                }
                                if (val == null || val.isEmpty()) {
                                    val = "0.00";
                                }
                                filled = filled.replaceFirst("\\|\\s{8}", "| " + val + " ");
                            }
                            break;
                        }
                    }
                    sb.append(filled).append("\n");
                } else {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }
        return md;
    }

    /** 替换模板中的描述性占位文本 */
    private String replaceTemplateText(String md, ReportTask task, Map<String, String> data) {
        String result = md;

        // 营收来源描述
        if (result.contains("营业收入主要来源是XXXX")) {
            result = result.replace("营业收入主要来源是XXXX",
                    "营业收入主要来源是" + data.getOrDefault("营收来源描述", "主营业务"));
        }
        // 利润为负原因
        if (result.contains("企业利润为负是由于XXXX")) {
            result = result.replace("企业利润为负是由于XXXX",
                    "企业利润为负是由于" + data.getOrDefault("利润为负原因", "暂不适用"));
        }
        // 是否覆盖
        if (result.contains("能/不能")) {
            result = result.replace("能/不能", data.getOrDefault("是否覆盖本息", "能"));
        }
        // 固定收入组成
        if (result.contains("XXXXXXXXXXXX")) {
            result = result.replace("XXXXXXXXXXXX", data.getOrDefault("固定收入组成", "长期合同和协议"));
        }
        // 审计机构
        if (result.contains("xxxx 会计师事务所")) {
            result = result.replace("xxxx 会计师事务所",
                    data.getOrDefault("审计机构", "xxxx 会计师事务所"));
        }

        // 营收预测表
        if (result.contains("2025年           | 2024年           | 2025年")) {
            result = result.replaceFirst(
                    "主营业务\\s*\\|\\s*\\|\\s*\\|",
                    "主营业务 | " + data.getOrDefault("营业收入预测2025", "9,800.00") + " | "
                            + data.getOrDefault("营业成本预测2025", "7,052.00") + " | "
                            + data.getOrDefault("销售利润预测2025", "2,748.00") + " |");
        }

        // 表格中的空单元格再次填充（兜底）
        result = result.replaceAll("\\|\\s{8}\\|", "| 0.00       |");

        return result;
    }

    // ============================================================
    // 模板字段加载
    // ============================================================

    /** 从 report_templates.json 加载指定模板的全部字段列表 */
    private List<String> getAllFieldsForTemplate(String templateId) {
        Map<String, Object> template = findTemplateById(templateId);
        if (template == null) return List.of();

        @SuppressWarnings("unchecked")
        List<String> allFields = (List<String>) template.get("all_fields");
        return allFields != null ? allFields : List.of();
    }

    /** 获取字段的中文显示标签 */
    private String getFieldLabel(String fieldName) {
        // 常用字段标签映射
        Map<String, String> labelMap = new LinkedHashMap<>();
        labelMap.put("企业名称", "企业名称");
        labelMap.put("统一信用代码", "统一社会信用代码");
        labelMap.put("主营业务", "主营业务");
        labelMap.put("员工人数", "员工人数");
        labelMap.put("营业收入2024", "营业收入（2024年）");
        labelMap.put("营业收入2023", "营业收入（2023年）");
        labelMap.put("营业收入2022", "营业收入（2022年）");
        labelMap.put("营业成本2024", "营业成本（2024年）");
        labelMap.put("营业成本2023", "营业成本（2023年）");
        labelMap.put("营业成本2022", "营业成本（2022年）");
        labelMap.put("净利润2024", "净利润（2024年）");
        labelMap.put("净利润2023", "净利润（2023年）");
        labelMap.put("净利润2022", "净利润（2022年）");
        labelMap.put("总资产2024", "总资产（2024年）");
        labelMap.put("总资产2023", "总资产（2023年）");
        labelMap.put("总资产2022", "总资产（2022年）");
        labelMap.put("资产负债率", "资产负债率");
        labelMap.put("货币资金202212", "货币资金（2022/12）");
        labelMap.put("货币资金202312", "货币资金（2023/12）");
        labelMap.put("货币资金202412", "货币资金（2024/12）");
        labelMap.put("货币资金202509", "货币资金（2025/09）");
        labelMap.put("应收账款202212", "应收账款（2022/12）");
        labelMap.put("应收账款202312", "应收账款（2023/12）");
        labelMap.put("应收账款202412", "应收账款（2024/12）");
        labelMap.put("应收账款202509", "应收账款（2025/09）");
        labelMap.put("预付账款202212", "预付账款（2022/12）");
        labelMap.put("预付账款202312", "预付账款（2023/12）");
        labelMap.put("预付账款202412", "预付账款（2024/12）");
        labelMap.put("预付账款202509", "预付账款（2025/09）");
        labelMap.put("其他应收款202212", "其他应收款（2022/12）");
        labelMap.put("其他应收款202312", "其他应收款（2023/12）");
        labelMap.put("其他应收款202412", "其他应收款（2024/12）");
        labelMap.put("其他应收款202509", "其他应收款（2025/09）");
        labelMap.put("营业收入预测2025", "营业收入预测（2025年）");
        labelMap.put("营业成本预测2025", "营业成本预测（2025年）");
        labelMap.put("销售利润预测2025", "销售利润预测（2025年）");
        labelMap.put("营收来源描述", "营收来源描述");
        labelMap.put("利润为负原因", "利润为负原因");
        labelMap.put("是否覆盖本息", "是否覆盖本息");
        labelMap.put("固定收入组成", "固定收入组成");
        labelMap.put("审计机构", "审计机构");
        labelMap.put("前五大客户占比", "前五大客户占比");
        labelMap.put("关联交易额", "关联交易额（万元）");
        labelMap.put("偿债能力评分", "偿债能力评分");
        labelMap.put("盈利能力评分", "盈利能力评分");
        labelMap.put("经营能力评分", "经营能力评分");
        labelMap.put("发展能力评分", "发展能力评分");
        labelMap.put("担保能力评分", "担保能力评分");
        labelMap.put("综合评分", "综合评分");
        labelMap.put("信用等级", "信用等级");
        labelMap.put("建议授信额度", "建议授信额度（万元）");
        labelMap.put("授信期限", "授信期限");
        labelMap.put("担保方式", "担保方式");
        labelMap.put("行业前景", "行业前景");
        labelMap.put("法定代表人", "法定代表人");
        labelMap.put("注册资本", "注册资本");
        labelMap.put("成立日期", "成立日期");
        labelMap.put("经营范围", "经营范围");
        labelMap.put("注册地址", "注册地址");
        return labelMap.getOrDefault(fieldName, fieldName);
    }

    /** 从 report_templates.json 查找模板 */
    private Map<String, Object> findTemplateById(String templateId) {
        try {
            String json = loadJsonFile("data/report_templates.json");
            if (json == null) {
                json = loadJsonFile("data-template/report_templates.json");
            }
            if (json == null) {
                json = loadJsonFile("report_templates.json");
            }
            if (json == null) return null;
            Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> templates = (List<Map<String, Object>>) root.get("templates");
            if (templates == null) return null;
            return templates.stream()
                    .filter(t -> templateId.equals(t.get("id")))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("加载模板配置失败: templateId={}, error={}", templateId, e.getMessage());
            return null;
        }
    }

    /** 加载 JSON 文件（支持磁盘和 classpath） */
    private String loadJsonFile(String path) {
        // 1. 磁盘路径
        try {
            Path diskPath = Paths.get(path);
            if (Files.exists(diskPath)) {
                return Files.readString(diskPath);
            }
        } catch (Exception ignored) {}
        // 2. classpath
        try {
            ClassPathResource res = new ClassPathResource(path);
            if (res.exists()) {
                try (InputStream is = res.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
