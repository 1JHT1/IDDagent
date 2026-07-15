package com.cropagent.auth;

import com.cropagent.config.AppConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final int expireHours;




    /*
    * 如果配置类里没有写secret，则这里构建一个jwt，但后果是服务重启的一瞬间，系统中所有在线用户的 JWT 令牌全部作废！
    * 前端带着旧的 token 请求接口，后端验签失败，
    * 统统返回 401。所有用户（包括管理员）都被强制登出，需要重新登录问题所在
    * */
    public JwtUtil(AppConfig config) {
        String secret = config.getJwt().getSecret();//获取jwt里的secret
        if (secret == null || secret.isEmpty()) {
            byte[] randomBytes = new byte[32];
            new SecureRandom().nextBytes(randomBytes);
            secret = HexFormat.of().formatHex(randomBytes);
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireHours = config.getJwt().getExpireHours();
    }


    /*
    * Jwt令牌生成
    * */
    public String createToken(String userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)//用户的唯一id
                .claim("username", username)//令牌塞进一个自定义字段username
                .issuedAt(Date.from(now))//签发时间
                .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))//盖上过期时间
                .signWith(secretKey)//拿着之前准备好的 secretKey（即 JWT_SECRET），给整个令牌生成一个数字签名
                .compact();//上面所有内容压缩成一串长长的、由 . 分隔的字符串
    }


    /*
    * 解析token功能
    * */
    public Claims decodeToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
