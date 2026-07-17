package com.IDDagent.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 聊天附件上传 / 下载控制器
 * - POST /api/chat/attachments        上传附件（需登录）
 * - GET  /api/chat/attachments/{id}/{filename}  下载/预览附件（公开）
 */
@RestController
@RequestMapping("/api/chat/attachments")
public class ChatAttachmentController {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentController.class);
    private static final String UPLOAD_DIR = "static/uploads/chat_attachments";
    /** 单文件大小上限：20MB */
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024;
    /** 合法的 fileId 格式（UUID，防止路径穿越） */
    private static final Pattern FILE_ID_PATTERN = Pattern.compile("^[a-f0-9\\-]{36}$");

    /**
     * 上传聊天附件
     */
    @PostMapping
    public Mono<Map<String, Object>> upload(@RequestPart("file") FilePart file) {
        return readFilePart(file)
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> {
                    if (bytes.length == 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
                    }
                    if (bytes.length > MAX_FILE_SIZE) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过 20MB 限制");
                    }

                    String originalName = sanitizeFilename(file.filename());
                    String fileId = UUID.randomUUID().toString();
                    Path dir = Paths.get(UPLOAD_DIR, fileId);
                    try {
                        Files.createDirectories(dir);
                        Files.write(dir.resolve(originalName), bytes);
                    } catch (IOException e) {
                        log.error("保存附件失败: {}", e.getMessage());
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存附件失败");
                    }

                    String encodedName = URLEncoder.encode(originalName, StandardCharsets.UTF_8)
                            .replace("+", "%20");
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("file_id", fileId);
                    resp.put("name", originalName);
                    resp.put("size", bytes.length);
                    resp.put("type", guessContentType(originalName));
                    resp.put("url", "/api/chat/attachments/" + fileId + "/" + encodedName);
                    log.info("附件上传成功: {} ({} bytes), id={}", originalName, bytes.length, fileId);
                    return resp;
                });
    }

    /**
     * 下载 / 预览附件
     */
    @GetMapping("/{fileId}/{filename}")
    public Mono<ResponseEntity<Resource>> download(@PathVariable String fileId,
                                                   @PathVariable String filename) {
        return Mono.fromCallable(() -> {
            if (!FILE_ID_PATTERN.matcher(fileId).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的文件ID");
            }
            String safeName = sanitizeFilename(filename);
            Path filePath = Paths.get(UPLOAD_DIR, fileId, safeName);
            if (!Files.exists(filePath)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
            }

            String contentType = guessContentType(safeName);
            String encodedName = URLEncoder.encode(safeName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            // 图片/PDF 内联预览，其他类型作为附件下载
            String disposition = (contentType.startsWith("image/") || contentType.equals("application/pdf"))
                    ? "inline" : "attachment";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            disposition + "; filename*=UTF-8''" + encodedName)
                    .body((Resource) new FileSystemResource(filePath));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // === Helpers ===

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

    /** 清理文件名，防止路径穿越 */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "unnamed";
        String name = Paths.get(filename).getFileName().toString();
        // 去掉可能引发问题的字符
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.isBlank() ? "unnamed" : name;
    }

    /** 根据扩展名推断 Content-Type */
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
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (lower.endsWith(".zip")) return "application/zip";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
