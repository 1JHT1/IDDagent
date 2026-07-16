package com.IDDagent.controller;
/*
* ----健康检查，所有访问
* */
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Mono<Map<String, Object>> healthCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("service", "智能尽调智能体");
        result.put("version", "1.0.0");
        result.put("timestamp", Instant.now().toString());
        return Mono.just(result);
    }
}
