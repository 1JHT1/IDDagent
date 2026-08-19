package com.IDDagent.controller;

import com.IDDagent.service.ContextMemoryService;
import com.IDDagent.service.FileParserService;
import com.IDDagent.service.LLMFieldExtractor;
import com.IDDagent.service.ReportStoreService;
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
    private final ContextMemoryService contextMemoryService;
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

    public ReportController(ReportTaskStore taskStore, FileParserService fileParser,
                            LLMFieldExtractor llmFieldExtractor, ContextMemoryService contextMemoryService) {
        this.taskStore = taskStore;
        this.fileParser = fileParser;
        this.llmFieldExtractor = llmFieldExtractor;
        this.contextMemoryService = contextMemoryService;
        try { Files.createDirectories(UPLOAD_DIR); } catch (Exception ignored) {}
    }

    /** 获取报告模板列表（供 H5 页面使用），支持按机构过滤 */
    @GetMapping(value = "/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getTemplates(
            @RequestParam(value = "organization", required = false) String organization) {
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
            // 按机构过滤：只返回匹配机构或无机构的模板
            if (organization != null && !organization.isEmpty()) {
                templates = templates.stream()
                        .filter(t -> {
                            String org = (String) t.getOrDefault("organization", "");
                            return org.isEmpty() || organization.equals(org);
                        })
                        .toList();
            }
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

    /**
     * 从已上传附件中解析企业名称与统一信用代码（供 H5 上传页自动填充只读字段）。
     * 与 startGeneration 内的完整财务解析不同，此接口只提取企业主体信息，用于上传阶段即时预览。
     */
    @PostMapping(value = "/parse-company", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> parseCompany(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> fileIds = (List<String>) body.getOrDefault("fileIds", List.of());
        if (fileIds == null || fileIds.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "fileIds 不能为空")));
        }
        // LLM 阻塞解析放到 boundedElastic 线程，不阻塞 Netty 事件循环
        return Mono.fromCallable(() -> {
            StringBuilder allRawText = new StringBuilder();
            for (String fileId : fileIds) {
                try {
                    Path filePath = findUploadedFile(fileId);
                    if (filePath != null && Files.exists(filePath)) {
                        String rawText = fileParser.extractText(filePath);
                        if (rawText != null && !rawText.isEmpty()) {
                            allRawText.append("=== 文件: ").append(filePath.getFileName()).append(" ===\n");
                            allRawText.append(rawText).append("\n\n");
                        }
                    } else {
                        log.warn("parse-company 上传文件未找到: fileId={}", fileId);
                    }
                } catch (Exception e) {
                    log.warn("parse-company 提取文件文本失败: fileId={}, error={}", fileId, e.getMessage());
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("companyName", "");
            result.put("creditCode", "");
            if (allRawText.isEmpty()) {
                return ResponseEntity.ok(result);
            }
            Map<String, String> info = llmFieldExtractor.extractCompanyInfo(allRawText.toString());
            if (info != null) {
                result.put("companyName", info.getOrDefault("companyName", ""));
                result.put("creditCode", info.getOrDefault("creditCode", ""));
            }
            log.info("parse-company 完成: fileIds={}, companyName={}, creditCode={}",
                    fileIds, result.get("companyName"), result.get("creditCode"));
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic());
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
        String organization = (String) body.getOrDefault("organization", "");
        String conversationId = (String) body.getOrDefault("conversationId", "");
        // 实证：H5 生成请求未携带 conversationId（旧标签页/旧链接进入）会导致任务无法关联回会话，
        // 穿插挂起恢复时按会话解析不到 report_id（日志可见根因）
        if (conversationId == null || conversationId.isBlank()) {
            log.warn("报告任务创建请求未携带 conversationId（H5 旧标签页进入？），任务将无法关联回会话: templateId={}", templateId);
        }

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
                creditCode, userId, sourceFile, organization, conversationId, attachmentNames, attachmentFileIds);

        // 将报告任务 report_id 关联到该会话的"生成尽调报告"步骤（激活态当前步骤或穿插挂起快照）：
        // 报告生成中穿插新意图时步骤 params 尚无 report_id（仅 reportComplete 收尾时写回），任务创建后
        // 立即写回，使穿插挂起恢复（confirmResume）时能解析到 report_id 重发报告生成进度卡片
        linkReportToConversationStep(conversationId, task.getReportId());

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

    /**
     * 将报告任务 report_id 关联到会话的"生成尽调报告"步骤：优先写回激活态当前步骤（WAITING_EXTERNAL），
     * 再检查穿插挂起快照（suspendPlan 深拷贝后同对象回填，恢复时 params 保留），命中其一即可。
     */
    private void linkReportToConversationStep(String conversationId, String reportId) {
        if (conversationId == null || conversationId.isBlank()
                || reportId == null || reportId.isBlank()) {
            if (conversationId != null && conversationId.isBlank()) {
                log.warn("跳过报告任务 {} 与会话的关联：请求 conversationId 为空（H5 未携带，任务归属丢失）", reportId);
            }
            return;
        }
        ContextMemoryService.ConversationContext ctx = contextMemoryService.get(conversationId);
        if (ctx == null) return;
        ContextMemoryService.PlanStep cur = contextMemoryService.getCurrentPlanStep(conversationId);
        if (cur != null && "generate_report".equals(cur.skill)
                && cur.status == ContextMemoryService.PlanStatus.WAITING_EXTERNAL) {
            cur.params.put("report_id", reportId);
            log.info("已关联报告任务 {} 到会话 {} 激活态报告步骤", reportId, conversationId);
        }
        if (contextMemoryService.hasSuspendedPlan(conversationId)) {
            int idx = ctx.suspendedIndex;
            if (idx >= 0 && idx < ctx.suspendedPlan.size()) {
                ContextMemoryService.PlanStep sp = ctx.suspendedPlan.get(idx);
                if ("generate_report".equals(sp.skill)
                        && sp.status == ContextMemoryService.PlanStatus.WAITING_EXTERNAL) {
                    sp.params.put("report_id", reportId);
                    log.info("已关联报告任务 {} 到会话 {} 挂起快照报告步骤", reportId, conversationId);
                }
            }
        }
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

    /** 打印确认接口（仅用户确认成功打印后调用）。报告已由生成流程入库，此处不再重复保存 */
    @PostMapping(value = "/{reportId}/print-log", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> recordPrintLog(@PathVariable String reportId) {
        ReportTask task = taskStore.getTask(reportId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("error", "报告不存在"));
        }
        if (!"completed".equals(task.getStatus())) {
            return ResponseEntity.status(400).body(Map.of("error", "报告尚未生成完成"));
        }
        return ResponseEntity.ok(Map.of("success", true));
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
            // 仅"确认并生成报告"触发的生成在完成时入库（data/report.json）
            CompletableFuture.runAsync(() -> generateReport(task, true));
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
            item.put("createdAt", task.getCreatedAt().toString());
            // 报告所属会话：前端据此只将进度卡注入对应会话的消息流，
            // 避免新建对话/切换会话后旧会话的生成中报告串入当前会话
            item.put("conversationId", task.getConversationId());
            return item;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reports", reports);
        return ResponseEntity.ok(result);
    }

    /** 按对话 ID 获取待处理报告（供 H5 新标签页生成报告后，原聊天页轮询获取进度卡片） */
    @GetMapping(value = "/conversation/{conversationId}/pending", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getPendingReportsByConversation(@PathVariable String conversationId) {
        List<ReportTask> pending = taskStore.getTasksByConversation(conversationId);
        List<Map<String, Object>> reports = pending.stream().map(task -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reportId", task.getReportId());
            item.put("status", task.getStatus());
            item.put("templateName", task.getTemplateName());
            item.put("companyName", task.getCompanyName());
            item.put("progress", task.getProgress());
            item.put("createdAt", task.getCreatedAt().toString());
            // 报告所属会话：供前端防切会话瞬间旧响应晚到导致的跨会话注入
            item.put("conversationId", task.getConversationId());
            // 完成时间供前端将完成卡排序到穿插意图之后（生成中任务为 null）
            item.put("completedAt", task.getCompletedAt() != null ? task.getCompletedAt().toString() : null);
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
        generateReport(task, false);
    }

    /** @param persistOnComplete 生成完成时是否写入 data/report.json（仅"确认并生成报告"触发时 true） */
    private void generateReport(ReportTask task, boolean persistOnComplete) {
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

            // 仅"确认并生成报告"（/update）触发的生成完成时入库（data/report.json），无需等待打印确认
            if (persistOnComplete) {
                ReportStoreService.saveReportJson(
                        task.getReportId(),
                        task.getCreditCode() != null ? task.getCreditCode() : "",
                        task.getCompanyName(),
                        task.getTemplateName(),
                        task.getOrganization() != null ? task.getOrganization() : "",
                        task.getContent(),
                        task.getCompletedAt(),
                        buildAttachmentList(task)
                );
            }

        } catch (Exception e) {
            if (task.getGenerationVersion() <= myVersion) {
                task.setStatus("failed");
                task.setErrorMessage("生成异常: " + e.getMessage());
            }
            log.error("报告生成失败: {}", task.getReportId(), e);
        }
    }

    /** 构建附件文件名列表（供报告入库使用） */
    private List<Map<String, Object>> buildAttachmentList(ReportTask task) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        List<String> fileIds = task.getAttachmentFileIds();
        List<String> names = task.getAttachmentNames();
        if (fileIds != null && names != null) {
            for (int i = 0; i < fileIds.size() && i < names.size(); i++) {
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("file_id", fileIds.get(i));
                att.put("file_name", names.get(i));
                attachments.add(att);
            }
        }
        return attachments;
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
                String value = entry.getValue() != null ? entry.getValue() : "";
                // 数值类字段为空时用 — 占位，避免出现裸"万元"等
                if (value.isEmpty() && isNumericField(entry.getKey())) {
                    value = "—";
                }
                result = result.replace(key, value);
            }
        }

        // 1.5 派生占位符：根据「是否覆盖本息」推断营业成本覆盖情况
        String coverCost = "不能".equals(data.getOrDefault("是否覆盖本息", "")) ? "无法" : "可以";
        result = result.replace("{{\u8986\u76d6\u8425\u4e1a\u6210\u672c}}", coverCost);

        // 1.6 派生占位符：根据附件解析的净利润自动判断利润正负，生成利润情况描述
        result = result.replace("{{\u5229\u6da6\u60c5\u51b5\u63cf\u8ff0}}", buildProfitDescription(data));

        // 2. 填充表格中空的数据单元格
        //    匹配模式: |        | （8个空格）
        result = fillTableCells(result, task.getTemplateId(), data);

        // 3. 描述性占位文本（必须先于通用 XXXX 替换执行，否则占位符会被企业名称提前破坏）
        result = replaceTemplateText(result, task, data);

        // 4. 剩余 XXXX 为企业名称占位
        result = result.replace("XXXX", task.getCompanyName());

        // 5. 兼容旧模板的 xx万元/XX万元 占位（按年份顺序依次替换，不再全部使用同一数值）
        result = replaceRevenuePlaceholders(result, data);

        // 6. 兜底：未替换的 {{占位符}} 统一清空
        result = result.replaceAll("\\{\\{\\{?[^}]+\\}\\}?", "");

        return result;
    }

    /**
     * 根据附件解析的净利润自动判断利润正负，生成描述文字（模板 {{利润情况描述}}）
     * - 净利润为负：企业利润为负（有原因则附上原因）
     * - 净利润为正：企业利润为正
     * - 无净利润数据：以财务数据为准
     */
    private String buildProfitDescription(Map<String, String> data) {
        String[] profitKeys = {"净利润2024", "净利润2023", "净利润2022"};
        String latest = "";
        for (String key : profitKeys) {
            String v = data.getOrDefault(key, "");
            if (v != null && !v.trim().isEmpty()) {
                latest = v.trim();
                break;
            }
        }
        if (latest.isEmpty()) {
            return "企业利润情况以财务数据为准";
        }
        boolean negative;
        String cleaned = latest.replace(",", "").replace("，", "").trim();
        if (cleaned.startsWith("(") || cleaned.startsWith("（") || cleaned.startsWith("-")
                || cleaned.startsWith("负") || cleaned.contains("亏损")) {
            negative = true;
        } else {
            try {
                negative = Double.parseDouble(cleaned) < 0;
            } catch (NumberFormatException e) {
                negative = cleaned.contains("-") || cleaned.contains("负");
            }
        }
        if (!negative) {
            return "企业利润为正";
        }
        String reason = data.getOrDefault("利润为负原因", "");
        if (reason != null && !reason.trim().isEmpty()) {
            return "企业利润为负，是由于" + reason.trim();
        }
        return "企业利润为负";
    }

    /** 判断字段是否为数值类字段（空值时用 — 占位，避免裸"万元"） */
    private boolean isNumericField(String field) {
        return field.matches(".*\\d{4}$") || "资产负债率".equals(field);
    }

    /** 统计固定收入组成的数量（按顿号/逗号/分号分隔） */
    private int countIncomeParts(String text) {
        String[] parts = text.split("[、，,；;]");
        int count = 0;
        for (String p : parts) {
            if (!p.trim().isEmpty()) count++;
        }
        return Math.max(count, 1);
    }

    /**
     * 旧模板兼容：按文字顺序（2022/2023/2024）依次将 xx万元/XX万元 占位替换为对应年份营业收入
     * 注意：模板句子"2022年至2024年营业收入分别为xx万元、xx万元、xx万元"中占位顺序与年份顺序一致
     */
    private String replaceRevenuePlaceholders(String result, Map<String, String> data) {
        if (!result.contains("XX万元") && !result.contains("xx万元")) return result;
        String[] revenueKeys = {"营业收入2022", "营业收入2023", "营业收入2024"};
        for (String key : revenueKeys) {
            String value = data.getOrDefault(key, "");
            if (value == null || value.trim().isEmpty()) value = "—";
            if (result.contains("XX万元")) {
                result = result.replaceFirst("XX万元", value + "万元");
            }
            if (result.contains("xx万元")) {
                result = result.replaceFirst("xx万元", value + "万元");
            }
        }
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
            boolean revenueTablePassed = false; // 已越过主营业务表表头，待转换为行指标格式
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
                                    val = "—";
                                }
                                filled = filled.replaceFirst("\\|\\s{8}", "| " + val + " ");
                            }
                            break;
                        }
                    }
                    sb.append(filled).append("\n");
                } else if (line.contains("营业收入（万元）")) {
                    // 主营业务表（9列）转换为行指标表格：与（二）财务指标表渲染样式一致
                    revenueTablePassed = true;
                    sb.append("| 项目       | 2024年 | 2023年 | 2022年 |\n");
                    sb.append("| ---------- | ------ | ------ | ------ |\n");
                } else if (revenueTablePassed && line.contains("主营业务")) {
                    // 输出 营业收入/营业成本/销售利润 三行指标数据
                    sb.append(buildIndicatorRows(data));
                    revenueTablePassed = false;
                } else if (revenueTablePassed) {
                    // 跳过原 9 列表格的年份行/空行（不输出）
                } else {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }
        return md;
    }

    /**
     * 生成行指标格式数据行（与（二）财务指标表样式一致）：
     * | 营业收入   | 2024 | 2023 | 2022 |
     * | 营业成本   | ... |
     * | 销售利润   | ... |（缺失时由 营业收入-营业成本 派生）
     */
    private String buildIndicatorRows(Map<String, String> data) {
        String[][] groups = {
                {"营业收入", "营业收入2024", "营业收入2023", "营业收入2022"},
                {"营业成本", "营业成本2024", "营业成本2023", "营业成本2022"},
                {"销售利润", "销售利润2024", "销售利润2023", "销售利润2022"}
        };
        StringBuilder sb = new StringBuilder();
        for (String[] group : groups) {
            sb.append("| ").append(group[0]).append("   |");
            for (int i = 1; i < group.length; i++) {
                String key = group[i];
                String val = data.getOrDefault(key, "");
                if (val != null && !val.trim().isEmpty()) {
                    val = val.trim();
                } else if ("销售利润".equals(group[0])) {
                    String year = key.substring(key.length() - 4);
                    String derived = deriveSalesProfit(data, year);
                    val = derived != null ? derived : "—";
                } else {
                    val = "—";
                }
                sb.append(" ").append(val).append(" |");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 销售利润派生：营业收入 - 营业成本（均缺失时返回 null） */
    private String deriveSalesProfit(Map<String, String> data, String year) {
        String rev = data.getOrDefault("营业收入" + year, "").trim();
        String cost = data.getOrDefault("营业成本" + year, "").trim();
        if (!rev.isEmpty() && !cost.isEmpty()) {
            try {
                double r = Double.parseDouble(rev.replace(",", "").replace("，", ""));
                double c = Double.parseDouble(cost.replace(",", "").replace("，", ""));
                return String.format("%.2f", r - c);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** 替换模板中的描述性占位文本 */
    private String replaceTemplateText(String md, ReportTask task, Map<String, String> data) {
        String result = md;

        // 营收来源描述（新模板：xxxx；旧模板：XXXX 兼容）
        if (result.contains("营业收入主要来源是xxxx")) {
            result = result.replace("营业收入主要来源是xxxx",
                    "营业收入主要来源是" + data.getOrDefault("营收来源描述", ""));
        } else if (result.contains("营业收入主要来源是XXXX")) {
            result = result.replace("营业收入主要来源是XXXX",
                    "营业收入主要来源是" + data.getOrDefault("营收来源描述", ""));
        }
        // 利润正负描述：根据附件净利润自动判断（新模板：企业利润为负主要是由于xxxx）
        if (result.contains("企业利润为负主要是由于xxxx")) {
            result = result.replace("企业利润为负主要是由于xxxx", buildProfitDescription(data));
        } else if (result.contains("企业利润为负是由于XXXX")) {
            result = result.replace("企业利润为负是由于XXXX",
                    "企业利润为负是由于" + data.getOrDefault("利润为负原因", ""));
        }
        // 营业成本覆盖情况（新模板：无法/可以）
        if (result.contains("无法/可以")) {
            String coverCost = "不能".equals(data.getOrDefault("是否覆盖本息", "")) ? "无法" : "可以";
            result = result.replace("无法/可以", coverCost);
        }
        // 是否覆盖本息（旧模板兼容：能/不能）
        if (result.contains("能/不能")) {
            result = result.replace("能/不能", data.getOrDefault("是否覆盖本息", ""));
        }
        // 固定收入组成（新模板：x部分组成：xxxxxx）
        if (result.contains("x部分组成：xxxxxx")) {
            String fixedIncome = data.getOrDefault("固定收入组成", "").trim();
            if (fixedIncome.isEmpty()) {
                result = result.replace("x部分组成：xxxxxx", "");
            } else {
                result = result.replace("x部分组成：xxxxxx",
                        countIncomeParts(fixedIncome) + "部分组成：" + fixedIncome);
            }
        }
        // 固定收入组成（旧模板兼容：XXXXXXXXXXXX）
        if (result.contains("XXXXXXXXXXXX")) {
            result = result.replace("XXXXXXXXXXXX", data.getOrDefault("固定收入组成", ""));
        }
        // 审计机构
        if (result.contains("xxxx 会计师事务所")) {
            result = result.replace("xxxx 会计师事务所",
                    data.getOrDefault("审计机构", "xxxx 会计师事务所"));
        }

        // 表格中的空单元格再次填充（兜底）：无数据时用 — 占位，避免写死 0.00 与真实数据混淆
        result = result.replaceAll("\\|\\s{8}\\|", "| — |");

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
        labelMap.put("营业收入2024", "营业收入（2024年）");
        labelMap.put("营业收入2023", "营业收入（2023年）");
        labelMap.put("营业收入2022", "营业收入（2022年）");
        labelMap.put("营业成本2024", "营业成本（2024年）");
        labelMap.put("营业成本2023", "营业成本（2023年）");
        labelMap.put("营业成本2022", "营业成本（2022年）");
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
