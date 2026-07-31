package com.IDDagent;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IDDAgentApplication {

    public static void main(String[] args) {
        // 加载 .env 文件（若存在），将变量设为系统属性，供 application.yml 的 ${...} 占位符使用
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));

        SpringApplication.run(IDDAgentApplication.class, args);
    }
}
