package com.cropagent.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public class PasswordUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    /*
    * 用于管理密码
    * */
    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        String hash = sha256(password + saltHex);
        return saltHex + "$" + hash;
    }

    public static boolean verifyPassword(String password, String hashed) {
        String[] parts = hashed.split("\\$", 2);
        if (parts.length != 2) return false;
        String salt = parts[0];
        String storedHash = parts[1];
        String computed = sha256(password + salt);
        return MessageDigest.isEqual(
                computed.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                storedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }


    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
