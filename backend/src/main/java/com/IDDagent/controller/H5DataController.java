package com.IDDagent.controller;

import com.IDDagent.skill.DataLoader;
import com.IDDagent.skill.InformationCheckSkill;
import com.IDDagent.skill.RiskCheckSkill;
import com.IDDagent.model.UserInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
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

    // Excel template download & upload customer list removed

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


    private static final Logger log = LoggerFactory.getLogger(H5DataController.class);
}
