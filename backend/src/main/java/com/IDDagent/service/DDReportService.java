package com.IDDagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DDReportService {

    private static final Logger log = LoggerFactory.getLogger(DDReportService.class);
    private static final String REPORTS_FILE = "data-template/dd_reports.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    // { reportId: reportData }
    private final Map<String, Map<String, Object>> reports = new ConcurrentHashMap<>();
    // { creditCode: [reportId, ...] }
    private final Map<String, List<String>> creditIndex = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        load();
        log.info("DDReportService initialized with {} reports", reports.size());
    }

    // ============================================================
    // 查询方法
    // ============================================================

    /**
     * 按企业名称/编号 + 时间区间查询历史尽调报告
     *
     * @param creditCode 企业统一信用代码（可选）
     * @param companyName 企业名称（可选，用于模糊匹配后的精确查询）
     * @param dateFrom    开始日期（可选，格式 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm:ss）
     * @param dateTo      结束日期（可选）
     * @param userId      用户 ID（用于机构权限过滤）
     * @return 符合条件的报告列表
     */
    public List<Map<String, Object>> queryReports(String creditCode, String companyName,
                                                   String dateFrom, String dateTo,
                                                   String userId) {
        log.info("===== DDReport QUERY DIAGNOSTICS =====");
        log.info("Step0 - reports size: {}, creditIndex size: {}, creditIndex keys: {}",
                reports.size(), creditIndex.size(), creditIndex.keySet());
        log.info("Step0 - input: creditCode={}, companyName={}, dateFrom={}, dateTo={}, userId={}",
                creditCode, companyName, dateFrom, dateTo, userId);

        // 1. 按 creditCode 或全量筛选
        Set<String> candidateIds = new LinkedHashSet<>();
        if (creditCode != null && !creditCode.isEmpty()) {
            List<String> ids = creditIndex.getOrDefault(creditCode, List.of());
            log.info("Step1 - looking up creditCode={} in creditIndex, found IDs: {}",
                    creditCode, ids);
            candidateIds.addAll(ids);
        } else {
            candidateIds.addAll(reports.keySet());
        }
        log.info("Step1 - candidate count after creditCode filter: {}", candidateIds.size());

        // 2. 按企业名称筛选（若提供了名称但无信用代码）
        if (companyName != null && !companyName.isEmpty() && (creditCode == null || creditCode.isEmpty())) {
            int before = candidateIds.size();
            candidateIds.removeIf(id -> {
                Map<String, Object> report = reports.get(id);
                if (report == null) return true;
                String name = (String) report.getOrDefault("company_name", "");
                return !companyName.equals(name);
            });
            log.info("Step2 - companyName filter: {} -> {} (removed {})",
                    before, candidateIds.size(), before - candidateIds.size());
        } else {
            log.info("Step2 - SKIPPED (creditCode is set, bypassing company name filter)");
        }

        // 3. 按 date_range 时间区间筛选（与查询区间判断重叠）
        if ((dateFrom != null && !dateFrom.isEmpty()) || (dateTo != null && !dateTo.isEmpty())) {
            Instant fromInstant = parseDate(dateFrom);
            Instant toInstant = parseDate(dateTo);
            log.info("Step3 - date filter: fromInstant={}, toInstant={}", fromInstant, toInstant);
            int before = candidateIds.size();
            candidateIds.removeIf(id -> {
                Map<String, Object> report = reports.get(id);
                if (report == null) return true;
                @SuppressWarnings("unchecked")
                Map<String, Object> dateRange = (Map<String, Object>) report.getOrDefault("date_range", Map.of());
                String rangeFrom = (String) dateRange.getOrDefault("from", "");
                String rangeTo = (String) dateRange.getOrDefault("to", "");
                try {
                    // 排除：报告结束日期 早于 查询开始日期（报告在查询范围之前）
                    if (!rangeTo.isEmpty() && fromInstant != null) {
                        Instant rEnd = parseDate(rangeTo);
                        if (rEnd != null && rEnd.isBefore(fromInstant)) {
                            log.debug("  - Excluded {}: report end {} < query start {}", id, rangeTo, dateFrom);
                            return true;
                        }
                    }
                    // 排除：报告开始日期 晚于 查询结束日期（报告在查询范围之后）
                    if (!rangeFrom.isEmpty() && toInstant != null) {
                        Instant rStart = parseDate(rangeFrom);
                        if (rStart != null && rStart.isAfter(toInstant)) {
                            log.debug("  - Excluded {}: report start {} > query end {}", id, rangeFrom, dateTo);
                            return true;
                        }
                    }
                } catch (Exception e) {
                    log.warn("  - Excluded {} due to exception: {}", id, e.getMessage());
                    return true;
                }
                return false;
            });
            log.info("Step3 - after date filter: {} -> {} (removed {})",
                    before, candidateIds.size(), before - candidateIds.size());
        } else {
            log.info("Step3 - SKIPPED (no date range provided)");
        }

        // 4. 按机构权限过滤（用户只能看自己机构/同银行的报告）
        String userInst = getUserInstitution(userId);
        log.info("Step4 - user institution: '{}'", userInst);
        List<Map<String, Object>> result = new ArrayList<>();
        int filteredByInst = 0;
        for (String id : candidateIds) {
            Map<String, Object> report = reports.get(id);
            if (report == null) {
                log.debug("  - {}: report is null, skipping", id);
                continue;
            }

            // 机构权限过滤
            String reportInst = (String) report.getOrDefault("institution", "");
            if (userInst != null && !userInst.isEmpty()
                    && reportInst != null && !reportInst.isEmpty()
                    && !reportInst.equals(userInst)) {
                log.debug("  - {}: institution mismatch (report='{}' != user='{}')", id, reportInst, userInst);
                filteredByInst++;
                continue;
            }

            // 5. 过滤未完成的报告
            String status = (String) report.getOrDefault("status", "");
            if (!"completed".equals(status)) {
                log.debug("  - {}: status '{}' is not completed, skipping", id, status);
                continue;
            }

            // 构建列表项
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("report_id", report.get("report_id"));
            item.put("institution", report.get("institution"));
            item.put("company_name", report.get("company_name"));
            item.put("name", report.getOrDefault("name", report.get("company_name")));
            item.put("status", report.get("status"));
            item.put("status_label", "completed".equals(report.get("status")) ? "创建成功" : "未完成");
            item.put("created_at", report.get("created_at"));
            item.put("updated_at", report.get("updated_at"));
            item.put("template_type", report.getOrDefault("template_type", "标准"));
            result.add(item);
        }

        log.info("Step4 - institution filtered: {}, final result count: {}", filteredByInst, result.size());

        // 先按公司名排序，再按报告模板排序，再按时间排序
        result.sort((a, b) -> {
            String ca = (String) a.getOrDefault("company_name", "");
            String cb = (String) b.getOrDefault("company_name", "");
            int cmp = ca.compareToIgnoreCase(cb);
            if (cmp != 0) return cmp;

            String ta = (String) a.getOrDefault("template_type", "标准");
            String tb = (String) b.getOrDefault("template_type", "标准");
            cmp = ta.compareTo(tb);
            if (cmp != 0) return cmp;

            // 从 name 中取日期前缀（yyyy-MM-dd）做升序排列
            String na = (String) a.getOrDefault("name", "");
            String nb = (String) b.getOrDefault("name", "");
            String da = na.length() >= 10 ? na.substring(0, 10) : "";
            String db = nb.length() >= 10 ? nb.substring(0, 10) : "";
            return da.compareTo(db);
        });

        log.info("===== DDReport QUERY END: {} results =====", result.size());
        return result;
    }

    /**
     * 获取报告详情
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getReport(String reportId) {
        Map<String, Object> report = reports.get(reportId);
        if (report == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(report);
        // 将内部 report_id 字段暴露（保留已有键）
        return result;
    }

    // ============================================================
    // CRUD 操作
    // ============================================================

    /**
     * 更新报告内容（编辑保存）
     */
    public Map<String, Object> updateReport(String reportId, Map<String, Object> updates) {
        Map<String, Object> report = reports.get(reportId);
        if (report == null) return null;

        // 仅允许更新指定字段
        List<String> allowedFields = List.of("basic_info", "due_diligence", "products");
        for (String field : allowedFields) {
            if (updates.containsKey(field)) {
                report.put(field, updates.get(field));
            }
        }
        report.put("updated_at", Instant.now().toString());
        save();
        return new LinkedHashMap<>(report);
    }

    /**
     * 创建新报告
     */
    public Map<String, Object> createReport(Map<String, Object> reportData) {
        String reportId = "DD-" + java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
                + "-" + String.format("%03d", reports.size() + 1);

        // 确保 ID 唯一
        while (reports.containsKey(reportId)) {
            int seq = Integer.parseInt(reportId.substring(reportId.lastIndexOf('-') + 1));
            reportId = reportId.substring(0, reportId.lastIndexOf('-') + 1) + String.format("%03d", seq + 1);
        }

        String now = Instant.now().toString();
        reportData.put("report_id", reportId);
        reportData.put("created_at", now);
        reportData.put("updated_at", now);
        reportData.put("attachments", new ArrayList<>());
        if (!reportData.containsKey("status")) {
            reportData.put("status", "incomplete");
        }

        reports.put(reportId, reportData);
        String creditCode = (String) reportData.get("credit_code");
        if (creditCode != null && !creditCode.isEmpty()) {
            creditIndex.computeIfAbsent(creditCode, k -> new ArrayList<>()).add(reportId);
        }
        save();
        return new LinkedHashMap<>(reportData);
    }

    // ============================================================
    // 附件管理
    // ============================================================

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAttachments(String reportId) {
        Map<String, Object> report = reports.get(reportId);
        if (report == null) return List.of();
        Object atts = report.get("attachments");
        if (atts instanceof List) return (List<Map<String, Object>>) atts;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public void addAttachment(String reportId, Map<String, Object> attachment) {
        Map<String, Object> report = reports.get(reportId);
        if (report == null) return;
        List<Map<String, Object>> atts = (List<Map<String, Object>>) report.getOrDefault("attachments", new ArrayList<>());
        atts.add(attachment);
        report.put("attachments", atts);
        report.put("updated_at", Instant.now().toString());
        save();
    }

    @SuppressWarnings("unchecked")
    public void removeAttachment(String reportId, String fileId) {
        Map<String, Object> report = reports.get(reportId);
        if (report == null) return;
        List<Map<String, Object>> atts = (List<Map<String, Object>>) report.getOrDefault("attachments", new ArrayList<>());
        atts.removeIf(a -> fileId.equals(a.get("file_id")));
        report.put("attachments", atts);
        report.put("updated_at", Instant.now().toString());
        save();
    }

    // ============================================================
    // 持久化
    // ============================================================

    @SuppressWarnings("unchecked")
    private synchronized void save() {
        try {
            Path path = Paths.get(REPORTS_FILE);
            Files.createDirectories(path.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reports", new LinkedHashMap<>(reports));

            // 重建索引写入
            Map<String, List<String>> index = new LinkedHashMap<>();
            for (var entry : reports.entrySet()) {
                String creditCode = (String) entry.getValue().get("credit_code");
                if (creditCode != null && !creditCode.isEmpty()) {
                    index.computeIfAbsent(creditCode, k -> new ArrayList<>()).add(entry.getKey());
                }
            }
            data.put("index", index);

            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), data);
        } catch (IOException e) {
            log.error("Failed to save dd_reports: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        // 尝试多个可能路径：先试 data-template/，再试 data/
        String[] candidates = {"data-template/dd_reports.json", "data/dd_reports.json"};
        Map<String, Object> data = null;
        String loadedFrom = "";

        for (String filePath : candidates) {
            // 尝试从磁盘加载
            try {
                Path path = Paths.get(filePath);
                if (Files.exists(path)) {
                    data = mapper.readValue(path.toFile(), new TypeReference<>() {});
                    loadedFrom = path.toAbsolutePath().toString();
                    break;
                }
            } catch (IOException e) {
                log.warn("Failed to load {} from disk: {}", filePath, e.getMessage());
            }

            // 尝试从 classpath 加载
            if (data == null) {
                try {
                    ClassPathResource resource = new ClassPathResource(filePath);
                    if (resource.exists()) {
                        try (InputStream is = resource.getInputStream()) {
                            data = mapper.readValue(is, new TypeReference<>() {});
                            loadedFrom = "classpath:" + filePath;
                            break;
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to load {} from classpath: {}", filePath, e.getMessage());
                }
            }
        }

        // 解析数据
        if (data != null) {
            Map<String, Object> loadedReports = (Map<String, Object>) data.getOrDefault("reports", Map.of());
            for (var entry : loadedReports.entrySet()) {
                reports.put(entry.getKey(), (Map<String, Object>) entry.getValue());
            }
            Map<String, Object> loadedIndex = (Map<String, Object>) data.getOrDefault("index", Map.of());
            for (var entry : loadedIndex.entrySet()) {
                creditIndex.put(entry.getKey(), (List<String>) entry.getValue());
            }
            log.info("DDReportService loaded {} reports and {} credit entries from {}",
                    reports.size(), creditIndex.size(), loadedFrom);
        } else {
            log.error("dd_reports.json not found on disk or classpath (tried: {})", String.join(", ", candidates));
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 解析日期字符串，支持 "2025-01-01" 和 "2026-07-16T08:50:49.013027700Z" 两种格式
     */
    private Instant parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try {
            // 尝试 ISO 格式
            return Instant.parse(dateStr);
        } catch (DateTimeParseException e) {
            try {
                // 尝试日期格式 (yyyy-MM-dd) → 转为当天开始时间
                LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                return date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e2) {
                log.warn("Failed to parse date: {}", dateStr);
                return null;
            }
        }
    }

    /**
     * 获取用户所属机构（从 UserStoreService 查询）
     * 这里简化处理，通过查询已有用户数据获取
     */
    private String getUserInstitution(String userId) {
        if (userId == null || userId.isEmpty()) return null;
        try {
            Path path = Paths.get("data/users.json");
            if (Files.exists(path)) {
                Map<String, Object> users = mapper.readValue(path.toFile(), new TypeReference<>() {});
                Map<String, Object> user = (Map<String, Object>) users.get(userId);
                if (user != null) {
                    String inst = (String) user.get("bank_institution");
                    if (inst != null && !inst.isEmpty()) {
                        return "中国工商银行" + inst;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load user institution for {}: {}", userId, e.getMessage());
        }
        return null;
    }
}
