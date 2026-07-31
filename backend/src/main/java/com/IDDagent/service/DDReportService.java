package com.IDDagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DDReportService {

    private static final Logger log = LoggerFactory.getLogger(DDReportService.class);
    private static final String REPORTS_FILE = "data/report.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    // { reportId: reportData }
    private final Map<String, Map<String, Object>> reports = new ConcurrentHashMap<>();

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
        log.info("Step0 - reports size: {}", reports.size());
        log.info("Step0 - input: creditCode={}, companyName={}, dateFrom={}, dateTo={}, userId={}",
                creditCode, companyName, dateFrom, dateTo, userId);

        // 1. 按企业名称筛选（模糊匹配，只要公司名包含搜索关键词即可）
        Set<String> candidateIds = new LinkedHashSet<>(reports.keySet());
        if (companyName != null && !companyName.isEmpty()) {
            int before = candidateIds.size();
            candidateIds.removeIf(id -> {
                Map<String, Object> report = reports.get(id);
                if (report == null) return true;
                String name = (String) report.getOrDefault("company_name", "");
                return !(name.contains(companyName) || companyName.contains(name));
            });
            log.info("Step1 - companyName filter: {} -> {} (removed {})",
                    before, candidateIds.size(), before - candidateIds.size());
        } else {
            log.info("Step1 - no companyName filter, using all {} reports", candidateIds.size());
        }

        // 2. 按 generate_time 时间区间筛选
        if ((dateFrom != null && !dateFrom.isEmpty()) || (dateTo != null && !dateTo.isEmpty())) {
            Instant fromInstant = parseDate(dateFrom);
            Instant toInstant = parseDate(dateTo);
            log.info("Step2 - date filter: fromInstant={}, toInstant={}", fromInstant, toInstant);
            int before = candidateIds.size();
            candidateIds.removeIf(id -> {
                Map<String, Object> report = reports.get(id);
                if (report == null) return true;
                String generateTime = (String) report.getOrDefault("generate_time", "");
                if (generateTime.isEmpty()) return true;
                try {
                    Instant reportTime = Instant.parse(generateTime);
                    // 排除：报告时间 早于 查询开始日期
                    if (fromInstant != null && reportTime.isBefore(fromInstant)) {
                        log.debug("  - Excluded {}: report time {} < query start {}", id, generateTime, dateFrom);
                        return true;
                    }
                    // 排除：报告时间 晚于 查询结束日期
                    if (toInstant != null && reportTime.isAfter(toInstant)) {
                        log.debug("  - Excluded {}: report time {} > query end {}", id, generateTime, dateTo);
                        return true;
                    }
                } catch (Exception e) {
                    log.warn("  - Excluded {} due to parse error: {}", id, e.getMessage());
                    return true;
                }
                return false;
            });
            log.info("Step2 - after date filter: {} -> {} (removed {})",
                    before, candidateIds.size(), before - candidateIds.size());
        } else {
            log.info("Step2 - SKIPPED (no date range provided)");
        }

        // 3. 按机构权限过滤
        String userInst = getUserInstitution(userId);
        log.info("Step3 - user institution: '{}'", userInst);
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

            // 4. 过滤未完成的报告（report.json 中无 status 的视为已完成）
            String status = (String) report.getOrDefault("status", "");
            if (!status.isEmpty() && !"completed".equals(status)) {
                log.debug("  - {}: status '{}' is not completed, skipping", id, status);
                continue;
            }

            // 构建列表项
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("report_id", report.get("report_id"));
            item.put("institution", report.get("institution"));
            item.put("company_name", report.get("company_name"));
            item.put("name", report.getOrDefault("company_name", ""));
            item.put("status", "completed");
            item.put("status_label", "创建成功");
            item.put("created_at", report.get("generate_time"));
            item.put("updated_at", report.get("generate_time"));
            item.put("template_type", report.getOrDefault("template_name", "标准"));
            // 附带附件列表，供前端直接使用下载链接
            Object rawAtts = report.get("attachments");
            if (rawAtts instanceof List) {
                item.put("attachments", rawAtts);
            } else {
                item.put("attachments", List.of());
            }
            result.add(item);
        }

        log.info("Step3 - institution filtered: {}, final result count: {}", filteredByInst, result.size());

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

            // 从 created_at 中取日期前缀做升序排列
            String da = (String) a.getOrDefault("created_at", "");
            String db = (String) b.getOrDefault("created_at", "");
            return da.compareTo(db);
        });

        log.info("===== DDReport QUERY END: {} results =====", result.size());
        return result;
    }

    /**
     * 获取报告详情
     */
    public Map<String, Object> getReport(String reportId) {
        Map<String, Object> report = reports.get(reportId);
        if (report == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(report);
        // 将内部 report_id 字段暴露（保留已有键）
        return result;
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

    /**
     * 获取 report.json 中所有不重复的公司名称（用于技能层的名称匹配和候选展示）
     */
    public List<String> getAllCompanyNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> report : reports.values()) {
            String name = (String) report.get("company_name");
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    // ============================================================
    // 持久化
    // ============================================================

    @SuppressWarnings("unchecked")
    private void load() {
        // 从 data/report.json 加载
        try {
            Path path = Paths.get(REPORTS_FILE);
            if (!Files.exists(path)) {
                log.error("report.json not found at {}", path.toAbsolutePath());
                return;
            }
            Map<String, Object> data = mapper.readValue(path.toFile(), new TypeReference<>() {});
            // report.json 结构: { "key_时间_公司_模板": { report_id, company_name, institution, template_name, generate_time, content, attachments } }
            for (var entry : data.entrySet()) {
                Map<String, Object> reportData = (Map<String, Object>) entry.getValue();
                String reportId = (String) reportData.get("report_id");
                if (reportId != null && !reportId.isEmpty()) {
                    reports.put(reportId, reportData);
                }
            }
            log.info("DDReportService loaded {} reports from {}", reports.size(), path.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to load report.json: {}", e.getMessage());
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
                        return inst;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load user institution for {}: {}", userId, e.getMessage());
        }
        return null;
    }
}
