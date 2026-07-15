package com.cropagent.controller;

import com.cropagent.auth.JwtUtil;
import com.cropagent.auth.UserStore;
import com.cropagent.model.*;
import com.cropagent.service.UserStoreService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserStoreService userStoreService;
    private final JwtUtil jwtUtil;

    public AuthController(UserStoreService userStoreService, JwtUtil jwtUtil) {
        this.userStoreService = userStoreService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/auth/register")
    public Mono<TokenResponse> register(@RequestBody UserRegister body) {
        return Mono.fromCallable(() -> {
            var result = userStoreService.createUser(body.getUsername(), body.getPassword(), body.getBankInstitution());
            if (result.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
            }
            Map<String, String> user = result.get();
            String token = jwtUtil.createToken(user.get("id"), user.get("username"));
            return new TokenResponse(token, "bearer", userStoreService.toUserInfo(user));
        });
    }

    @PostMapping("/auth/login")
    public Mono<TokenResponse> login(@RequestBody UserLogin body) {
        return Mono.fromCallable(() -> {
            var result = userStoreService.authenticate(body.getUsername(), body.getPassword(), body.getBankInstitution());
            if (result.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
            }
            Map<String, String> user = result.get();
            String token = jwtUtil.createToken(user.get("id"), user.get("username"));
            return new TokenResponse(token, "bearer", userStoreService.toUserInfo(user));
        });
    }

    @GetMapping("/user/me")
    public Mono<UserInfo> getMe(@RequestAttribute("currentUser") UserInfo currentUser) {
        return Mono.just(currentUser);
    }

    /*查找已注册用户*//*可删除*/
    @GetMapping("/users")
    public List<Map<String, String>> listAllUsers() {
        return userStoreService.getSafeUserList();
    }
}
