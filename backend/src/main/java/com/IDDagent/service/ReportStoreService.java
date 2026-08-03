package com.IDDagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告存储服务，负责将生成的完整报告和打印日志持久化到 data/report.json
 *
 * 完整报告格式：{ "yyyyMMdd_HHmmss_公司名_模板名": { report_id, generate_time, template_name, company_name, credit_code, institution, content } }
 * 打印日志格式：{ "yyyyMMdd_HHmmss_公司名_模板名": { generate_time, template_name, company_name, institution } }
 */
@Service
public class ReportStoreService {

    private static final Logger log = LoggerFactory.getLogger(ReportStoreService.class);
    private static final String PRINT_LOG_FILE = "data/report.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 生成 key：yyyyMMdd_HHmmss_公司名_模板名 */
    public static String generateTitle(Instant timestamp, String companyName, String templateName) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(ZoneId.systemDefault());
        String timeStr = fmt.format(timestamp);
        String safeCompany = companyName != null ? companyName.replaceAll("[\\\\/:*?\"<>|]", "_") : "";
        String safeTemplate = templateName != null ? templateName.replaceAll("[\\\\/:*?\"<>|]", "_") : "";
        return timeStr + "_" + safeCompany + "_" + safeTemplate;
    }

    /** 保存生成的完整报告到 data/report.json（含 markdown 内容，供他人直接读取） */
    public static synchronized void saveReportJson(
            String reportId, String creditCode, String companyName, String templateName, String organization,
            String content, Instant completedAt,
            List<Map<String, Object>> attachments) {
        try {
            Path path = Paths.get(PRINT_LOG_FILE);
            Files.createDirectories(path.getParent());

            // 读取已有数据
            Map<String, Object> existing = new LinkedHashMap<>();
            if (Files.exists(path)) {
                existing = mapper.readValue(path.toFile(), LinkedHashMap.class);
            }

            // 检查是否已有相同 reportId 的记录，有则更新
            String existingKey = null;
            for (Map.Entry<String, Object> e : existing.entrySet()) {
                Object val = e.getValue();
                if (val instanceof Map) {
                    Object rid = ((Map<?, ?>) val).get("report_id");
                    if (reportId != null && reportId.equals(rid)) {
                        existingKey = e.getKey();
                        break;
                    }
                }
            }

            String generateTime = completedAt != null ? completedAt.toString() : Instant.now().toString();
            String key = generateTitle(completedAt != null ? completedAt : Instant.now(),
                    companyName != null ? companyName : "",
                    templateName != null ? templateName : "");

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("report_id", reportId != null ? reportId : "");
            entry.put("generate_time", generateTime);
            entry.put("template_name", templateName != null ? templateName : "");
            entry.put("credit_code", creditCode != null ? creditCode : "");
            entry.put("company_name", companyName != null ? companyName : "");
            entry.put("institution", organization != null ? organization : "");
            entry.put("content", content != null ? content : "");
            entry.put("attachments", attachments != null ? attachments : List.of());

            if (existingKey != null) {
                // 已存在 → 更新（保留原 key 防止时间戳变化）
                existing.put(existingKey, entry);
                log.info("Report updated in report.json: key={}, contentLength={}",
                        existingKey, content != null ? content.length() : 0);
            } else {
                // 不存在 → 追加
                existing.put(key, entry);
                log.info("Report saved to report.json: key={}, contentLength={}",
                        key, content != null ? content.length() : 0);
            }
            mapper.writeValue(path.toFile(), existing);
        } catch (IOException e) {
            log.error("Failed to save report to report.json: {}", e.getMessage());
        }
    }

    /** 保存打印日志到 data/report.json（仅用户确认成功打印后调用） */
    public static synchronized void savePrintLog(String companyName,
                                                  String templateName, String organization) {
        try {
            Path path = Paths.get(PRINT_LOG_FILE);
            Files.createDirectories(path.getParent());

            // 读取已有的打印日志
            Map<String, Object> existing = new LinkedHashMap<>();
            if (Files.exists(path)) {
                existing = mapper.readValue(path.toFile(), LinkedHashMap.class);
            }

            String generateTime = Instant.now().toString();
            String key = generateTitle(Instant.now(),
                    companyName != null ? companyName : "",
                    templateName != null ? templateName : "");

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("generate_time", generateTime);
            entry.put("template_name", templateName != null ? templateName : "");
            entry.put("company_name", companyName != null ? companyName : "");
            entry.put("institution", organization != null ? organization : "");

            existing.put(key, entry);
            mapper.writeValue(path.toFile(), existing);
            log.info("Print log saved: key={}, company={}, template={}", key, companyName, templateName);
        } catch (IOException e) {
            log.error("Failed to save print log: {}", e.getMessage());
        }
    }
}
