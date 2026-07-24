package com.IDDagent.controller;

import com.IDDagent.model.UserInfo;
import com.IDDagent.service.DDReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/dd-reports")
public class HistoricalDDReportController {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDDReportController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ATTACHMENT_DIR = "static/uploads/dd_reports";
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024;
    private static final Pattern FILE_ID_PATTERN = Pattern.compile("^[a-f0-9\\-]{36}$");

    private final DDReportService ddReportService;

    public HistoricalDDReportController(DDReportService ddReportService) {
        this.ddReportService = ddReportService;
    }

    /**
     * 获取报告详情
     */
    @GetMapping("/{report_id}")
    public Mono<Map<String, Object>> getReport(
            @PathVariable String report_id) {
        return Mono.fromCallable(() -> {
            Map<String, Object> report = ddReportService.getReport(report_id);
            if (report == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
            }
            return report;
        });
    }

    /**
     * 更新报告内容（编辑保存）
     */
    @PutMapping("/{report_id}")
    public Mono<Map<String, Object>> updateReport(
            @PathVariable String report_id,
            @RequestBody Map<String, Object> body,
            @RequestAttribute("currentUser") UserInfo currentUser) {
        return Mono.fromCallable(() -> {
            Map<String, Object> report = ddReportService.updateReport(report_id, body);
            if (report == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("message", "报告已更新");
            response.put("report", report);
            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 下载报告文件（JSON 格式，供客户端 PDF 生成使用）
     */
    @GetMapping("/{report_id}/download")
    public Mono<ResponseEntity<ByteArrayResource>> downloadReport(
            @PathVariable String report_id) {
        return Mono.fromCallable(() -> {
            Map<String, Object> report = ddReportService.getReport(report_id);
            if (report == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
            }
            String status = (String) report.get("status");
            if (!"completed".equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅创建成功的报告可以下载");
            }

            String content = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            ByteArrayResource resource = new ByteArrayResource(bytes);

            String reportName = (String) report.getOrDefault("name", "report");
            String filename = URLEncoder.encode(reportName + ".json", StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + filename)
                    .body(resource);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取报告附件列表
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/{report_id}/attachments")
    public Mono<List<Map<String, Object>>> getAttachments(
            @PathVariable String report_id) {
        return Mono.fromCallable(() -> {
            Map<String, Object> report = ddReportService.getReport(report_id);
            if (report == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
            }
            return ddReportService.getAttachments(report_id);
        });
    }

    /**
     * 上传报告附件
     */
    @PostMapping("/{report_id}/attachments")
    public Mono<Map<String, Object>> uploadAttachment(
            @PathVariable String report_id,
            @RequestPart("file") FilePart file,
            @RequestAttribute("currentUser") UserInfo currentUser) {
        return readFilePart(file)
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> {
                    if (bytes.length == 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
                    }
                    if (bytes.length > MAX_FILE_SIZE) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过 20MB 限制");
                    }

                    Map<String, Object> report = ddReportService.getReport(report_id);
                    if (report == null) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
                    }

                    String originalName = sanitizeFilename(file.filename());
                    String fileId = UUID.randomUUID().toString();
                    Path dir = Paths.get(ATTACHMENT_DIR, report_id);
                    try {
                        Files.createDirectories(dir);
                        Files.write(dir.resolve(originalName), bytes);
                    } catch (IOException e) {
                        log.error("保存附件失败: {}", e.getMessage());
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存附件失败");
                    }

                    Map<String, Object> attachment = new LinkedHashMap<>();
                    attachment.put("file_id", fileId);
                    attachment.put("name", originalName);
                    attachment.put("size", bytes.length);
                    attachment.put("type", guessContentType(originalName));
                    attachment.put("url", "/api/chat/attachments/" + fileId + "/" +
                            URLEncoder.encode(originalName, StandardCharsets.UTF_8).replace("+", "%20"));
                    attachment.put("uploaded_at", java.time.Instant.now().toString());

                    ddReportService.addAttachment(report_id, attachment);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "ok");
                    response.put("attachment", attachment);
                    response.put("message", "附件上传成功");
                    log.info("DDReport attachment uploaded: report={}, file={}", report_id, originalName);
                    return response;
                });
    }

    /**
     * 删除报告附件
     */
    @DeleteMapping("/{report_id}/attachments/{fileId}")
    public Mono<Map<String, Object>> deleteAttachment(
            @PathVariable String report_id,
            @PathVariable String fileId,
            @RequestAttribute("currentUser") UserInfo currentUser) {
        return Mono.fromCallable(() -> {
            Map<String, Object> report = ddReportService.getReport(report_id);
            if (report == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
            }
            ddReportService.removeAttachment(report_id, fileId);
            // 尝试删除物理文件
            try {
                Path dir = Paths.get(ATTACHMENT_DIR, report_id);
                if (Files.exists(dir)) {
                    try (var files = Files.list(dir)) {
                        files.filter(f -> f.getFileName().toString().contains(fileId))
                                .findFirst()
                                .ifPresent(f -> {
                                    try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                                });
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to delete attachment file: {}", e.getMessage());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("message", "附件已删除");
            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private Mono<byte[]> readFilePart(FilePart filePart) {
        if (filePart == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少文件"));
        }
        return filePart.content()
                .collectList()
                .map(dataBuffers -> {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    for (var buf : dataBuffers) {
                        byte[] b = new byte[buf.readableByteCount()];
                        buf.read(b);
                        try { baos.write(b); } catch (IOException ignored) {}
                    }
                    return baos.toByteArray();
                });
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "unnamed";
        String name = Paths.get(filename).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.isBlank() ? "unnamed" : name;
    }

    private String guessContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /**
     * 根据报告数据生成独立的 HTML 下载页面
     */
}
