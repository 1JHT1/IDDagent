package com.IDDagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Component
public class AccountOpeningSkill {

    private static final Logger log = LoggerFactory.getLogger(AccountOpeningSkill.class);
    private static final String ACCOUNT_FILE = "data/account_opening.json";
    private static final String NAME_INDEX_FILE = "data/company_name_index.json";
    private static final String RISK_FILE = "data/risk_check.json";
    private static final String OUTREACH_FILE = "data/customer_outreach.json";
    private static final String PRODUCT_FILE = "data/product_recommendations.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SkillRegistry registry;

    public AccountOpeningSkill(SkillRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new Skill(
                "open_corporate_account",
                "当用户表示客户已同意办理开户、需要协助开户时调用此技能。" +
                        "例如「客户已同意办理开户，请协助办理」「帮我给XX企业开户」「准备开户资料」等。" +
                        "返回资料上传链接，支持客户经理上传营业执照等文件后自动预填开户信息。",
                this::handle,
                Map.of(
                        "company_name", new Skill.SkillParam("string", "企业名称（可选，可从上下文自动获取）", false, "北京星河科技有限公司"),
                        "credit_code", new Skill.SkillParam("string", "企业统一信用代码（可选）", false, "91110108MA01B3XK2P")
                )
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handle(String userId, Map<String, Object> params) {
        String companyName = ((String) params.getOrDefault("company_name", "")).trim();
        String creditCode = ((String) params.getOrDefault("credit_code", "")).trim();
        String conversationId = (String) params.getOrDefault("_conversation_id", "");
        String baseUrl = DataLoader.buildBaseUrl();

        Map<String, String> nameIndex = (Map<String, String>) (Map<?, ?>) DataLoader.loadJson(NAME_INDEX_FILE);

        // Resolve company name
        if (!companyName.isEmpty() && creditCode.isEmpty()) {
            Map<String, Object> resolved = RiskCheckSkill.resolveCompanyMatch(companyName, nameIndex);
            if (resolved.containsKey("credit_code")) {
                creditCode = (String) resolved.get("credit_code");
                companyName = nameIndex.getOrDefault(creditCode, companyName);
            } else if (resolved.containsKey("action")) {
                return resolved;
            }
        }

        // Reverse lookup credit code -> company name
        if (!creditCode.isEmpty() && companyName.isEmpty()) {
            companyName = nameIndex.getOrDefault(creditCode, "");
        }

        if (creditCode.isEmpty() && companyName.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("action", "not_found");
            resp.put("message", "请先指定要开户的企业。您可以提供企业名称或统一信用代码。");
            return resp;
        }

        // Create application
        Map<String, Object> appData = loadAccountData();
        String appId = "app-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String now = Instant.now().toString();

        Map<String, Object> applications = (Map<String, Object>) appData.getOrDefault("applications", new LinkedHashMap<>());
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("id", appId);
        app.put("user_id", userId);
        app.put("conversation_id", conversationId);
        app.put("company_name", companyName);
        app.put("credit_code", creditCode);
        app.put("status", "upload");
        app.put("documents", new LinkedHashMap<>());
        app.put("form_data", new LinkedHashMap<>());
        app.put("created_at", now);
        app.put("submitted_at", null);
        applications.put(appId, app);
        appData.put("applications", applications);
        saveAccountData(appData);

        String uploadUrl = baseUrl + "/h5/account-upload.html?app_id=" + appId
                + "&company_name=" + URLEncoder.encode(companyName, StandardCharsets.UTF_8)
                + "&credit_code=" + URLEncoder.encode(creditCode, StandardCharsets.UTF_8)
                + "&conversation_id=" + URLEncoder.encode(conversationId, StandardCharsets.UTF_8);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "result");
        result.put("app_id", appId);
        result.put("company_name", companyName);
        result.put("credit_code", creditCode);
        result.put("status", "upload");
        result.put("upload_url", uploadUrl);
        result.put("required_documents", List.of(
                "营业执照正本（清晰扫描件或照片）",
                "法定代表人身份证（正反面）",
                "公章印模（如有）"));
        return result;
    }

    // === Mock OCR + Prefill (public for API controller use) ===

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mockOcrAndPrefill(String creditCode, String companyName) {
        Map<String, Object> riskData = DataLoader.loadJson(RISK_FILE);
        Map<String, Object> outreachData = DataLoader.loadJson(OUTREACH_FILE);
        Map<String, Object> prodData = DataLoader.loadJson(PRODUCT_FILE);

        Map<String, Object> riskInfo = (Map<String, Object>) riskData.get(creditCode);
        Map<String, Object> outreachInfo = (Map<String, Object>) outreachData.get(creditCode);
        Map<String, Object> recs = DataLoader.getMap(prodData, "recommendations");
        Map<String, Object> prodInfo = DataLoader.getMap(recs, creditCode);

        // Company name
        String actualName = companyName;
        if (riskInfo != null && riskInfo.get("company_name") != null) actualName = (String) riskInfo.get("company_name");
        else if (outreachInfo != null && outreachInfo.get("company_name") != null) actualName = (String) outreachInfo.get("company_name");
        else if (prodInfo.get("company_name") != null) actualName = (String) prodInfo.get("company_name");

        // Address
        String address = "（请核实后填写）";
        if (outreachInfo != null) {
            address = (String) outreachInfo.getOrDefault("business_address",
                    outreachInfo.getOrDefault("registered_address", address));
        }

        // Risk
        String riskLevel = riskInfo != null ? (String) riskInfo.getOrDefault("risk_level", "low") : "low";

        // Analysis
        String analysis = (String) prodInfo.getOrDefault("analysis_summary", "");

        // Mock legal rep
        String hashSeed = md5Hex(creditCode).substring(0, 8);
        String mockLegalRep = Integer.parseInt(hashSeed.substring(0, 1), 16) % 2 == 0 ? "张明" : "张伟";
        String mockRepId = "4403" + hashSeed.substring(0, 4)
                + (Integer.parseInt(hashSeed.substring(4, 5), 16) % 2 == 0 ? "1975" : "1980")
                + "0" + (Integer.parseInt(hashSeed.substring(5, 6), 16) % 9 + 1) + "15" + hashSeed.substring(6, 8);
        String mockBeneficiary = Integer.parseInt(hashSeed.substring(2, 3), 16) % 2 == 0 ? "李华" : "李强";

        Map<String, Object> formData = new LinkedHashMap<>();

        // Company info
        Map<String, Object> companyInfo = new LinkedHashMap<>();
        companyInfo.put("company_name", actualName);
        companyInfo.put("credit_code", creditCode);
        companyInfo.put("registered_address", address);
        companyInfo.put("registered_capital", "（请核实后填写）");
        companyInfo.put("business_scope", "（请核实后填写）");
        companyInfo.put("legal_representative", mockLegalRep);
        companyInfo.put("legal_rep_id_number", mockRepId);
        companyInfo.put("legal_rep_phone", "138" + hashSeed);
        companyInfo.put("beneficiary_name", mockBeneficiary);
        companyInfo.put("beneficiary_id_number", "4403" + hashSeed.substring(2, 6) + "1985"
                + "0" + (Integer.parseInt(hashSeed.substring(7, 8), 16) % 9 + 1) + "20" + hashSeed.substring(0, 2));
        companyInfo.put("beneficiary_relationship", "实际控制人");
        formData.put("company_info", companyInfo);

        // Account info
        Map<String, Object> accountInfo = new LinkedHashMap<>();
        accountInfo.put("account_type", "基本户");
        accountInfo.put("currency", "人民币");
        accountInfo.put("account_number", generateAccountNumber());
        accountInfo.put("reserved_seal", "公章+法人章+财务章");
        accountInfo.put("reconciliation_method", "电子对账（企业网银）");
        formData.put("account_info", accountInfo);

        // Due diligence
        Map<String, Object> dueDiligence = new LinkedHashMap<>();
        dueDiligence.put("opening_purpose", "日常经营结算");
        dueDiligence.put("fund_source", "经营收入");
        dueDiligence.put("expected_transaction_volume", estimateVolume(riskInfo, analysis));
        dueDiligence.put("cross_border_involved", "否");
        Map<String, String> riskLabels = Map.of("low", "低风险", "medium", "中风险", "high", "高风险");
        dueDiligence.put("risk_rating", riskLabels.getOrDefault(riskLevel, "低风险"));
        dueDiligence.put("conclusion", "high".equals(riskLevel)
                ? "经尽职调查，该企业存在一定风险，建议加强尽调后审慎受理。"
                : "经尽职调查，该企业经营状况正常，风险可控，建议受理。");
        formData.put("due_diligence", dueDiligence);

        // Product signing
        Map<String, Object> productSigning = new LinkedHashMap<>();
        productSigning.put("enterprise_online_banking", true);
        productSigning.put("bank_enterprise_reconciliation", true);
        productSigning.put("enterprise_mobile_banking", true);
        productSigning.put("corporate_settlement_card", false);
        productSigning.put("payroll_service", false);
        productSigning.put("sms_notification", true);
        formData.put("product_signing", productSigning);

        return formData;
    }

    // === Helpers ===

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadAccountData() {
        Path path = Paths.get(ACCOUNT_FILE);
        if (Files.exists(path)) {
            try {
                return mapper.readValue(path.toFile(), LinkedHashMap.class);
            } catch (IOException e) {
                log.error("Failed to load account data: {}", e.getMessage());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applications", new LinkedHashMap<>());
        return data;
    }

    private void saveAccountData(Map<String, Object> data) {
        try {
            Path path = Paths.get(ACCOUNT_FILE);
            Files.createDirectories(path.getParent());
            mapper.writeValue(path.toFile(), data);
        } catch (IOException e) {
            log.error("Failed to save account data: {}", e.getMessage());
        }
    }

    private static String generateAccountNumber() {
        Random rand = new Random();
        StringBuilder sb = new StringBuilder("6225");
        for (int i = 0; i < 12; i++) sb.append(rand.nextInt(10));
        return sb.toString();
    }

    private static String estimateVolume(Map<String, Object> riskData, String analysis) {
        if (analysis.contains("亿") || analysis.contains("大")) return "年交易规模5000万-1亿";
        if (analysis.contains("中型") || analysis.contains("稳定")) return "年交易规模1000万-5000万";
        return "年交易规模500万-1000万";
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "00000000";
        }
    }
}
