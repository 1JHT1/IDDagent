package com.IDDagent.auth;

import com.IDDagent.model.UserInfo;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

@Component
@Order(1)
public class JwtAuthFilter implements WebFilter {

    //无须jwt校验就可以登录的URL白名单
    private static final List<Pattern> PUBLIC_PATHS = List.of(
            Pattern.compile("^/api/auth/.*"),
            Pattern.compile("^/api/health.*"),
            Pattern.compile("^/api/risk-report/.*"),
            Pattern.compile("^/api/outreach/.*"),
            Pattern.compile("^/api/product-recommend/.*"),
            Pattern.compile("^/api/account-opening/(upload|process|preview|update|submit|notify|notifications).*"),
            Pattern.compile("^/h5/.*"),
            Pattern.compile("^/docs.*"),
            Pattern.compile("^/openapi.*"),
            Pattern.compile("^/swagger.*"),
            Pattern.compile("^/webjars.*"),
            Pattern.compile("^/favicon.*"),
            Pattern.compile("^/api/users.*")
    );

    private final JwtUtil jwtUtil;
    private final UserStore userStore;

    public JwtAuthFilter(JwtUtil jwtUtil, UserStore userStore) {
        this.jwtUtil = jwtUtil;
        this.userStore = userStore;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = jwtUtil.decodeToken(token);
            String userId = claims.getSubject();
            if (userId == null) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            var userOpt = userStore.getUser(userId);
            if (userOpt.isEmpty()) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            UserInfo userInfo = userStore.toUserInfo(userOpt.get());
            exchange.getAttributes().put("currentUser", userInfo);
            return chain.filter(exchange);

        } catch (ExpiredJwtException e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        } catch (JwtException e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    private boolean isPublicPath(String path) {
        for (Pattern p : PUBLIC_PATHS) {
            if (p.matcher(path).matches()) return true;
        }
        return false;
    }
}
