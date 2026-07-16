package com.IDDagent.controller;

import com.IDDagent.skill.AccountOpeningSkill;
import com.IDDagent.service.ConversationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account-opening")
public class AccountOpeningController {

    private static final Logger log = LoggerFactory.getLogger(AccountOpeningController.class);
    private static final String ACCOUNT_DATA_FILE = "data/account_opening.json";
    private static final String UPLOAD_DIR = "static/uploads/account_opening";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConversationService conversationService;

    public AccountOpeningController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/upload")
    public Mono<Map<String, Object>> upload(
            @RequestPart("app_id") String appId,
            @RequestPart(value = "business_license", required = false) FilePart businessLicense,
            @RequestPart(value = "legal_rep_id", required = false) FilePart legalRepId) {

        // Read both file parts reactively
        Mono<byte[]> bizLicenseBytes = readFilePart(businessLicense);
        Mono<byte[]> legalRepBytes = readFilePart(legalRepId);

        return Mono.zip(bizLicenseBytes, legalRepBytes)
                .publishOn(Schedulers.boundedElastic())
                .map(tuple -> {
                    byte[] bizBytes = tuple.getT1();
                    byte[] legBytes = tuple.getT2();

                    Map<String, Object> apps = loadAccountApps();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> applications = (Map<String, Object>) apps.getOrDefault("applications", Map.of());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> app = (Map<String, Object>) applications.get(appId);

                    if (app == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开户申请不存在");
                    if (!"upload".equals(app.get("status")))
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前状态为" + app.get("status") + "，无法上传");

                    Path uploadDir = Paths.get(UPLOAD_DIR, appId);
                    try { Files.createDirectories(uploadDir); } catch (IOException e) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建上传目录失败");
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, String> documents = (Map<String, String>) app.getOrDefault("documents", new LinkedHashMap<>());
                    int savedCount = 0;

                    try {
                        if (bizBytes != null && bizBytes.length > 0 && businessLicense != null && businessLicense.filename() != null) {
                            String ext = getExtension(businessLicense.filename());
                            Path dest = uploadDir.resolve("business_license" + ext);
                            Files.write(dest, bizBytes);
                            documents.put("business_license", dest.toString());
                            savedCount++;
                        }
                        if (legBytes != null && legBytes.length > 0 && legalRepId != null && legalRepId.filename() != null) {
                            String ext = getExtension(legalRepId.filename());
                            Path dest = uploadDir.resolve("legal_rep_id" + ext);
                            Files.write(dest, legBytes);
                            documents.put("legal_rep_id", dest.toString());
                            savedCount++;
                        }
                    } catch (IOException e) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存文件失败: " + e.getMessage());
                    }

                    if (savedCount == 0)
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少上传一份资料");

                    app.put("documents", documents);
                    app.put("status", "processing");
                    saveAccountApps(apps);

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "ok");
                    response.put("app_id", appId);
                    response.put("next_step", "processing");
                    response.put("message", "资料上传成功，共 " + savedCount + " 份文件");
                    return response;
                });
    }

    @PostMapping("/process/{appId}")
    public Mono<Map<String, Object>> process(@PathVariable String appId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> apps = loadAccountApps();
            @SuppressWarnings("unchecked")
            Map<String, Object> applications = (Map<String, Object>) apps.getOrDefault("applications", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) applications.get(appId);

            if (app == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开户申请不存在");
            if (!"processing".equals(app.get("status")))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前状态为" + app.get("status") + "，无法处理");

            String creditCode = (String) app.get("credit_code");
            String companyName = (String) app.get("company_name");
            Map<String, Object> formData = AccountOpeningSkill.mockOcrAndPrefill(creditCode, companyName);
            app.put("form_data", formData);
            app.put("status", "preview");
            saveAccountApps(apps);

            String baseUrl = getBaseUrl();
            String convId = (String) app.getOrDefault("conversation_id", "");
            String previewUrl = baseUrl + "/h5/account-preview.html?app_id=" + appId
                    + "&conversation_id=" + URLEncoder.encode(convId, StandardCharsets.UTF_8);

            log.info("Prefill complete: {} ({})", companyName, creditCode);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("app_id", appId);
            response.put("preview_url", previewUrl);
            response.put("message", "数据预填完成，请预览确认");
            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/preview/{appId}")
    public Mono<Map<String, Object>> preview(@PathVariable String appId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> apps = loadAccountApps();
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) ((Map<String, Object>) apps.getOrDefault("applications", Map.of())).get(appId);

            if (app == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开户申请不存在");
            String status = (String) app.get("status");
            if (!"preview".equals(status) && !"submitted".equals(status))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前状态为" + status + "，无法预览");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("app_id", appId);
            response.put("app_status", status);
            response.put("company_name", app.get("company_name"));
            response.put("credit_code", app.get("credit_code"));
            response.put("form_data", app.get("form_data"));
            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/update/{appId}")
    public Mono<Map<String, Object>> update(@PathVariable String appId, @RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> apps = loadAccountApps();
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) ((Map<String, Object>) apps.getOrDefault("applications", Map.of())).get(appId);

            if (app == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开户申请不存在");
            if (!"preview".equals(app.get("status")))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前状态为" + app.get("status") + "，已提交后不可修改");

            @SuppressWarnings("unchecked")
            Map<String, Object> formData = (Map<String, Object>) body.get("form_data");
            if (formData == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供 form_data");

            app.put("form_data", formData);
            saveAccountApps(apps);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("app_id", appId);
            response.put("message", "表单数据已更新");
            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/submit/{appId}")
    public Mono<Map<String, Object>> submit(@PathVariable String appId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> apps = loadAccountApps();
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) ((Map<String, Object>) apps.getOrDefault("applications", Map.of())).get(appId);

            if (app == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开户申请不存在");
            if ("submitted".equals(app.get("status")))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该申请已提交，不可重复提交");
            if (!"preview".equals(app.get("status")))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前状态为" + app.get("status") + "，请先完成预览");

            app.put("status", "submitted");
            String submittedAt = Instant.now().toString();
            app.put("submitted_at", submittedAt);
            saveAccountApps(apps);

            String baseUrl = getBaseUrl();
            String submittedUrl = baseUrl + "/h5/account-submitted.html?app_id=" + appId;

            log.info("Submitted: {} ({})", app.get("company_name"), app.get("credit_code"));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("app_id", appId);
            response.put("submitted_url", submittedUrl);
            response.put("submitted_at", submittedAt);
            response.put("company_name", app.get("company_name"));
            response.put("message", "开户申请已成功提交！企业名称：" + app.get("company_name"));
            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/notify/{appId}")
    public Mono<Map<String, Object>> notify(@PathVariable String appId, @RequestBody(required = false) Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> apps = loadAccountApps();
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) ((Map<String, Object>) apps.getOrDefault("applications", Map.of())).get(appId);

            if (app == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开户申请不存在");

            String conversationId = body != null
                    ? (String) body.getOrDefault("conversation_id", app.getOrDefault("conversation_id", ""))
                    : (String) app.getOrDefault("conversation_id", "");

            if (conversationId.isEmpty()) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("status", "ok");
                resp.put("message", "无关联会话，跳过通知");
                return resp;
            }

            String baseUrl = getBaseUrl();
            String submittedUrl = baseUrl + "/h5/account-submitted.html?app_id=" + appId;

            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("type", "account_submitted");
            notification.put("app_id", appId);
            notification.put("company_name", app.getOrDefault("company_name", ""));
            notification.put("credit_code", app.getOrDefault("credit_code", ""));
            notification.put("submitted_url", submittedUrl);
            notification.put("submitted_at", app.getOrDefault("submitted_at", Instant.now().toString()));

            conversationService.addAccountNotification(conversationId, notification);
            log.info("Callback notification queued: conv={}, app={}, company={}", conversationId, appId, app.get("company_name"));

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("message", "通知已入队");
            return resp;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/notifications/{conversationId}")
    public Mono<Map<String, Object>> getNotifications(@PathVariable String conversationId) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> notifications = conversationService.popAccountNotifications(conversationId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("notifications", notifications);
            return resp;
        });
    }

    // === Helpers ===

    private Mono<byte[]> readFilePart(FilePart filePart) {
        if (filePart == null) return Mono.just(new byte[0]);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadAccountApps() {
        try {
            Path path = Paths.get(ACCOUNT_DATA_FILE);
            if (Files.exists(path)) {
                return mapper.readValue(path.toFile(), new TypeReference<>() {});
            }
        } catch (IOException e) {
            log.error("Failed to load account data: {}", e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applications", new LinkedHashMap<>());
        return data;
    }

    private void saveAccountApps(Map<String, Object> data) {
        try {
            Path path = Paths.get(ACCOUNT_DATA_FILE);
            Files.createDirectories(path.getParent());
            mapper.writeValue(path.toFile(), data);
        } catch (IOException e) {
            log.error("Failed to save account data: {}", e.getMessage());
        }
    }

    private String getBaseUrl() {
        String host = System.getenv().getOrDefault("HOST", "localhost");
        String port = System.getenv().getOrDefault("PORT", "8000");
        return "http://" + host + ":" + port;
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".jpg";
    }
}
