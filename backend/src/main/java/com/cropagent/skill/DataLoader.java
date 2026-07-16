package com.cropagent.skill;
/*
* 方法1-从指定的磁盘路径读取一个 JSON 文件，并将其解析成 Java 的 Map<String, Object> 对象
* 方法2-读取操作系统的环境变量（Environment Variables），拼接生成一个基础的 HTTP URL 字符串。
* 方法3-从一个大 Map 中根据 Key 取出一个子 Map，并做了类型安全检查。
* 方法4-从 Map 中根据 Key 取值，如果取不到（为 null），则返回调用方指定的默认值。
* */
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class DataLoader {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /*“JSON 文件持久化”的加载入口，通常会在服务启动时（@PostConstruct）被调用，把硬盘上的数据灌进内存缓存。
    * */
    public static Map<String, Object> loadJson(String filePath) {
        // 1. 尝试从磁盘相对路径加载
        try {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                return mapper.readValue(path.toFile(),
                        new TypeReference<Map<String, Object>>() {});
            }
        } catch (IOException e) {
            log.warn("Failed to load JSON from disk: {} - {}", filePath, e.getMessage());
        }

        // 2. 回退到 classpath 加载（兼容不同工作目录）
        try {
            ClassPathResource resource = new ClassPathResource(filePath);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return mapper.readValue(is,
                            new TypeReference<Map<String, Object>>() {});
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load JSON from classpath: {} - {}", filePath, e.getMessage());
        }

        log.error("JSON file not found on disk or classpath: {}", filePath);
        return Map.of();
    }

    public static String buildBaseUrl() {
        String host = System.getenv().getOrDefault("HOST", "localhost");
        String port = System.getenv().getOrDefault("PORT", "8000");
        return "http://" + host + ":" + port;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val instanceof Map) return (Map<String, Object>) val;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Map<String, Object> data, String key, T defaultValue) {
        Object val = data.get(key);
        if (val == null) return defaultValue;
        return (T) val;
    }
}
