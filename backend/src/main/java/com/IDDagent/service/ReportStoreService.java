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
import java.util.Map;

/**
 * 打印日志服务，负责将打印记录持久化到 data/report.json
 * 格式：{ "yyyyMMdd_HHmmss_公司名_模板名": { generate_time, template_name, company_name, institution } }
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

    /** 保存打印日志到 data/report.json */
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
