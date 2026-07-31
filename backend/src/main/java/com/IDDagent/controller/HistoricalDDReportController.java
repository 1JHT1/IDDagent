package com.IDDagent.controller;

import com.IDDagent.service.DDReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/dd-reports")
public class HistoricalDDReportController {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDDReportController.class);
    private static final ObjectMapper mapper = new ObjectMapper();

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
     * 获取报告附件列表（从 report.json 的 attachments 字段读取）
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
     * 下载附件（通过 fileId 从 data/uploads/report-files/ 中查找实际文件）
     */
    @GetMapping("/{report_id}/attachments/{fileId}/download")
    public Mono<ResponseEntity<ByteArrayResource>> downloadAttachmentFile(
            @PathVariable String report_id,
            @PathVariable String fileId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> report = ddReportService.getReport(report_id);
            if (report == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) report.get("attachments");
            if (attachments == null || attachments.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "无附件");
            }

            // 在固定目录 data/uploads/report-files/ 中按 fileId 前缀查找实际文件
            Path uploadDir = Paths.get("data", "uploads", "report-files");
            Path filePath;
            try (var stream = Files.list(uploadDir)) {
                filePath = stream.filter(f -> f.getFileName().toString().startsWith(fileId))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
            }

            // 获取原始文件名（从 attachments 中查找匹配的 file_id）
            String fileName = fileId;
            for (Map<String, Object> att : attachments) {
                String attFileId = (String) att.get("file_id");
                if (fileId.equals(attFileId)) {
                    fileName = (String) att.getOrDefault("file_name", fileId);
                    break;
                }
            }

            byte[] bytes = Files.readAllBytes(filePath);
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encodedName)
                    .body(new ByteArrayResource(bytes));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ============================================================
    // 工具方法
    // ============================================================

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
}
