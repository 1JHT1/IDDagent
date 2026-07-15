package com.cropagent.service;

import com.cropagent.auth.PasswordUtil;
import com.cropagent.model.UserInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
* 告诉Spring 这个类有IOC容器创建并管理
* */
@Component
public class UserStoreService {

    private static final Logger log = LoggerFactory.getLogger(UserStoreService.class);
    private static final String USERS_FILE = "data/users.json";

    private final Map<String, Map<String, String>> users = new ConcurrentHashMap<>();//存储用户的地方

    //创建了一个 Jackson 库的核心工具类 ObjectMapper 的实例,可以用来将Java 对象 和 JSON 字符串 之间的双向翻译。
    private final ObjectMapper objectMapper = new ObjectMapper();


    /*
    * 在这个 Bean（组件）被 Spring 创建并组装好所有依赖之后，自动执行这个方法，且只执行一次。”
    * */
    @PostConstruct
    public void init() {
        load();
    }

    /*
    * 从硬盘加载用户数据到内存
    * */
    private void load() {
        try {
            Path path = Paths.get(USERS_FILE);
            if (Files.exists(path)) {
                Map<String, Map<String, String>> loaded = objectMapper.readValue(
                        path.toFile(),
                        new TypeReference<Map<String, Map<String, String>>>() {});
                users.putAll(loaded);
                log.info("Loaded {} users from {}", users.size(), path.toAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("Failed to load users file: {}", e.getMessage());
        }
    }

    /*
    * 将内存中的用户保存到磁盘中，也就是我的文件夹（正常情况不会再控制台输出，但是比如磁盘空间占用完会catch输出异常）
    * */
    private synchronized void save() {
        try {
            Path path = Paths.get(USERS_FILE);
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), users);
        } catch (IOException e) {
            log.error("Failed to save users file: {}", e.getMessage());
        }
    }

    /*
    * 注册用户
    * */
    public Optional<Map<String, String>> createUser(String username, String password, String bankInstitution) {
        for (Map<String, String> u : users.values()) {
            if (username.equals(u.get("username"))) {
                return Optional.empty();
            }
        }
        //随机生产用户id
        String userId = UUID.randomUUID().toString();
        Map<String, String> user = new java.util.HashMap<>();
        user.put("id", userId);
        user.put("username", username);
        user.put("password", PasswordUtil.hashPassword(password));
        user.put("bank_institution", bankInstitution != null ? bankInstitution : "");
        user.put("created_at", Instant.now().toString());
        users.put(userId, user);//先放进内存
        save();//再放进磁盘
        return Optional.of(user);
    }

    /*
    * 判断用户名是否一致，密码是否一致，机构是否一致
    * */
    public Optional<Map<String, String>> authenticate(String username, String password, String bankInstitution) {
        for (Map<String, String> u : users.values()) {
            if (username.equals(u.get("username"))//前一个username是输入的用户名，后一个是map里的key对应一个用户名
                    && PasswordUtil.verifyPassword(password, u.get("password"))) {
                // 验证银行机构是否匹配
                String storedBank = u.get("bank_institution");
                if (storedBank != null && !storedBank.isEmpty()
                        && bankInstitution != null && !bankInstitution.isEmpty()
                        && !storedBank.equals(bankInstitution)) {
                    return Optional.empty();//返回空盒子
                }
                return Optional.of(u);//返回optional盒子，里面是登录成功的map
            }
        }
        return Optional.empty();//返回空盒子
    }

    /*
    * 通过用户的唯一 ID（userId），从内存仓库里把这个人‘捞’出来。如果找到了就装进盒子（Optional）还给你；如果没找到，就还你一个空盒子
    * 鉴权/授权时使用。验证“这个 Token 代表的用户是否还合法有效”。和前面的登录authenticate有区别
    * */
    public Optional<Map<String, String>> getUser(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    /*
    * 解决数据格式不统一的问题
    * */
    public UserInfo toUserInfo(Map<String, String> user) {
        return new UserInfo(
                user.get("id"),
                user.get("username"),
                user.get("bank_institution"),
                user.get("created_at"));
    }

    /*查看已经注册的用户信息
    * */
    // 返回用户列表，但去掉 password 字段，防止泄露
    /*
    * ----可删除
    * */
    public List<Map<String, String>> getSafeUserList() {
        return users.values().stream()
                .map(u -> {
                    Map<String, String> safe = new java.util.HashMap<>(u);
                    safe.remove("password"); // 去掉密码再返回
                    return safe;
                })
                .collect(java.util.stream.Collectors.toList());
    }
}
