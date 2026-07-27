package com.IDDagent.controller;

import com.IDDagent.skill.DataLoader;
import com.IDDagent.skill.InformationCheckSkill;
import com.IDDagent.skill.RiskCheckSkill;
import com.IDDagent.model.UserInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class H5DataController {

    private static final String DATA_DIR = "data-template";
    private static final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/risk-report/{creditCode}")
    public Mono<Map<String, Object>> getRiskReport(@PathVariable String creditCode) {
        return Mono.fromCallable(() -> {
            Map<String, Object> riskData = DataLoader.loadJson(DATA_DIR + "/risk_check.json");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) riskData.get(creditCode);
            if (result == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到信用代码 " + creditCode + " 的风险信息");
            }
            // 标准化模板数据：补全 company_name、risk_level、has_risk，统一 rongan/business_info items 结构
            return RiskCheckSkill.normalizeForH5(result);
        });
    }

    @GetMapping("/information-check/{creditCode}")
    public Mono<Map<String, Object>> getInformationCheck(@PathVariable String creditCode) {
        return Mono.fromCallable(() -> {
            Map<String, Object> checkData = DataLoader.loadJson(DATA_DIR + "/information_check.json");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) checkData.get(creditCode);
            if (result == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到信用代码 " + creditCode + " 的信息核实数据");
            }
            return InformationCheckSkill.normalizeForH5(result);
        });
    }

    @GetMapping("/outreach/{creditCode}")
    public Mono<Map<String, Object>> getOutreachData(@PathVariable String creditCode) {
        return Mono.fromCallable(() -> {
            Map<String, Object> outreachData = DataLoader.loadJson(DATA_DIR + "/customer_outreach.json");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) outreachData.get(creditCode);
            if (result == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到信用代码 " + creditCode + " 的拓户准备数据");
            }
            return result;
        });
    }

    // Excel template download
    @GetMapping("/customer-template")
    public Mono<ResponseEntity<ByteArrayResource>> getCustomerTemplate(
            @RequestAttribute("currentUser") UserInfo currentUser) {
        return Mono.fromCallable(() -> {
            try (Workbook wb = new XSSFWorkbook()) {
                Sheet sheet = wb.createSheet("客户清单");

                // Header style
                CellStyle headerStyle = wb.createCellStyle();
                Font headerFont = wb.createFont();
                headerFont.setFontName("微软雅黑");
                headerFont.setFontHeightInPoints((short) 11);
                headerFont.setBold(true);
                headerFont.setColor(IndexedColors.WHITE.getIndex());
                headerStyle.setFont(headerFont);
                headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
                headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                headerStyle.setAlignment(HorizontalAlignment.CENTER);
                headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
                headerStyle.setBorderBottom(BorderStyle.THIN);
                headerStyle.setBorderTop(BorderStyle.THIN);
                headerStyle.setBorderLeft(BorderStyle.THIN);
                headerStyle.setBorderRight(BorderStyle.THIN);

                // Data style
                CellStyle dataStyle = wb.createCellStyle();
                Font dataFont = wb.createFont();
                dataFont.setFontName("微软雅黑");
                dataFont.setFontHeightInPoints((short) 10);
                dataStyle.setFont(dataFont);
                dataStyle.setAlignment(HorizontalAlignment.LEFT);
                dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
                dataStyle.setBorderBottom(BorderStyle.THIN);
                dataStyle.setBorderTop(BorderStyle.THIN);
                dataStyle.setBorderLeft(BorderStyle.THIN);
                dataStyle.setBorderRight(BorderStyle.THIN);

                // Headers
                String[] headers = {"企业名称", "统一社会信用代码", "推荐得分(0-100)"};
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }

                // Sample data
                String[][] sampleData = {
                        {"示例科技有限公司", "91110108MA01B3XK2P", "85.5"},
                        {"示例供应链管理有限公司", "91440300MA5DCTJH8N", "78.0"}
                };
                for (int rowIdx = 0; rowIdx < sampleData.length; rowIdx++) {
                    Row row = sheet.createRow(rowIdx + 1);
                    for (int colIdx = 0; colIdx < sampleData[rowIdx].length; colIdx++) {
                        Cell cell = row.createCell(colIdx);
                        cell.setCellValue(sampleData[rowIdx][colIdx]);
                        cell.setCellStyle(dataStyle);
                    }
                }

                sheet.setColumnWidth(0, 30 * 256);
                sheet.setColumnWidth(1, 28 * 256);
                sheet.setColumnWidth(2, 18 * 256);
                sheet.createFreezePane(0, 1);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                wb.write(baos);
                byte[] bytes = baos.toByteArray();

                ByteArrayResource resource = new ByteArrayResource(bytes);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=customer_template.xlsx")
                        .body(resource);
            }
        });
    }

    // Upload customer Excel list
    @PostMapping("/customer-upload")
    public Mono<Map<String, Object>> uploadCustomerList(
            @RequestPart("file") FilePart file,
            @RequestParam(value = "mode", defaultValue = "overwrite") String mode,
            @RequestAttribute("currentUser") UserInfo currentUser) {

        String filename = file.filename();
        if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls"))) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传 .xlsx 或 .xls 格式的 Excel 文件"));
        }

        return file.content()
                .collectList()
                .map(dataBuffers -> {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    for (var buf : dataBuffers) {
                        byte[] b = new byte[buf.readableByteCount()];
                        buf.read(b);
                        try { baos.write(b); } catch (IOException ignored) {}
                    }
                    return baos.toByteArray();
                })
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> {

            // Parse Excel
            List<Map<String, Object>> newCustomers = new ArrayList<>();
            try (Workbook wb = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
                Sheet sheet = wb.getSheetAt(0);
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    Cell nameCell = row.getCell(0);
                    if (nameCell == null) continue;
                    String name = nameCell.getStringCellValue().trim();
                    String creditCode = "";
                    double score = 0.0;
                    if (row.getCell(1) != null) creditCode = row.getCell(1).getStringCellValue().trim();
                    if (row.getCell(2) != null) score = row.getCell(2).getNumericCellValue();
                    if (!name.isEmpty() && !creditCode.isEmpty()) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("name", name);
                        c.put("credit_code", creditCode);
                        c.put("score", score);
                        newCustomers.add(c);
                    }
                }
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Excel 文件解析失败: " + e.getMessage());
            }

            if (newCustomers.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Excel 文件中没有有效的客户数据");
            }

            // Save to JSON file
            String uploadFilePath = DATA_DIR + "/uploaded_customers.json";
            Path uploadPath = Paths.get(uploadFilePath);
            Map<String, List<Map<String, Object>>> allData = new LinkedHashMap<>();
            if (Files.exists(uploadPath)) {
                try {
                    allData = mapper.readValue(uploadPath.toFile(), new TypeReference<>() {});
                } catch (Exception ignored) {}
            }

            String userId = currentUser.getId();
            if ("append".equals(mode)) {
                List<Map<String, Object>> existing = allData.getOrDefault(userId, new ArrayList<>());
                Set<String> existingCodes = existing.stream()
                        .map(c -> (String) c.get("credit_code"))
                        .collect(Collectors.toSet());
                for (Map<String, Object> c : newCustomers) {
                    String code = (String) c.get("credit_code");
                    if (!existingCodes.contains(code)) {
                        existing.add(c);
                    } else {
                        for (int i = 0; i < existing.size(); i++) {
                            if (code.equals(existing.get(i).get("credit_code"))) {
                                existing.set(i, c);
                                break;
                            }
                        }
                    }
                }
                allData.put(userId, existing);
            } else {
                allData.put(userId, newCustomers);
            }

            try {
                Files.createDirectories(uploadPath.getParent());
                mapper.writeValue(uploadPath.toFile(), allData);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存文件失败: " + e.getMessage());
            }

            int total = allData.get(userId).size();
            log.info("User {} uploaded customer list (Excel), mode={}, total={}", userId, mode, total);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("mode", mode);
            response.put("total_count", total);
            response.put("message", ("append".equals(mode) ? "追加" : "覆盖") + "上传成功，当前共 " + total + " 条客户记录");
            return response;
        });
    }

    // ============================================================
    // Markdown 文件渲染 API（供 md-viewer.html 调用）
    // ============================================================

    @GetMapping(value = "/h5/markdown", produces = "text/markdown; charset=utf-8")
    public Mono<String> getMarkdown(@RequestParam("file") String fileName) {
        return Mono.fromCallable(() -> {
            // 安全校验：防止路径穿越
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的文件名");
            }
            String diskPath = "data/" + fileName;
            // 1. 尝试从磁盘 data/ 读取
            Path path = Paths.get(diskPath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                log.info("Loading markdown from disk: {}", path.toAbsolutePath());
                return Files.readString(path);
            }
            // 2. 回退到 classpath data/ 目录
            try {
                ClassPathResource resource = new ClassPathResource(diskPath);
                if (resource.exists()) {
                    log.info("Loading markdown from classpath: {}", diskPath);
                    try (InputStream is = resource.getInputStream()) {
                        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                                .lines().collect(Collectors.joining("\n"));
                    }
                }
            } catch (IOException ignored) {}
            // 3. 尝试 data-template/ 目录
            String altPath = "data-template/" + fileName;
            try {
                ClassPathResource altResource = new ClassPathResource(altPath);
                if (altResource.exists()) {
                    log.info("Loading markdown from classpath alt: {}", altPath);
                    try (InputStream is = altResource.getInputStream()) {
                        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                                .lines().collect(Collectors.joining("\n"));
                    }
                }
            } catch (IOException ignored) {}
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件 " + fileName + " 不存在");
        });
    }

    // ============================================================
    // Helper
    // ============================================================

    private Map<String, Object> loadJson(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) return Map.of();
            return mapper.readValue(path.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(H5DataController.class);
}
