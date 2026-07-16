package com.IDDagent.config;
/*
* Spring Boot 配置属性类（Configuration Properties Class），它的核心作用
* 是将 application-template.yml（或 application.properties）配置文件中的参数，统一映射成 Java 代码中可以调用的对象。
* */
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app")



public class AppConfig {
    private DeepSeek deepseek = new DeepSeek();
    private Model model = new Model();
    private Jwt jwt = new Jwt();
    private Data data = new Data();
    private Upload upload = new Upload();

    @Getter @Setter
    public static class DeepSeek {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com";
    }

    @Getter @Setter
    public static class Model {
        private String name = "deepseek-v4-flash";
        private String coordinator = "deepseek-chat";
        private String followUp = "deepseek-chat";
    }

    @Getter @Setter
    public static class Jwt {
        /*
        * JWT 令牌 = 印着名字的工作证。
        * JWT_SECRET = 人事部唯一的、保密的“钢印模具”。
        */
        private String secret = "";
        private int expireHours = 24;
    }

    @Getter @Setter
    public static class Data {
        private String dir = "data";
    }

    @Getter @Setter
    public static class Upload {
        private String dir = "static/uploads/account_opening";
    }
}
