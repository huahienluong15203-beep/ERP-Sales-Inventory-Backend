package com.erp.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
public class JwtUtils {

    @Value("${erp.app.jwtSecret}")
    private String jwtSecret;

    @Value("${erp.app.jwtExpirationMs}")
    private int jwtExpirationMs;

    // Lấy khoá bí mật từ chuỗi cấu hình để ký Token
    private Key key() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    // 1. Tạo chuỗi Token JWT kèm Session ID (Story S1-01 & Single Active Session)
    public String generateTokenFromUsernameAndSession(String username, String sessionId) {
        return Jwts.builder()
                .setSubject(username)
                .claim("sid", sessionId)
                .setIssuedAt(new Date())
                .setExpiration(new Date((new Date()).getTime() + jwtExpirationMs))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    public long getExpirationMs() {
        return jwtExpirationMs;
    }

    public String generateTokenFromUsername(String username) {
        return generateTokenFromUsernameAndSession(username, java.util.UUID.randomUUID().toString());
    }

    // 2. Trích xuất Username từ Token gửi lên (S1-02)
    public String getUsernameFromJwtToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    // Trích xuất Session ID (sid) từ Token gửi lên để kiểm tra đơn phiên
    public String getSessionIdFromJwtToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .get("sid", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    // 3. Trích xuất thời điểm phát hành (issuedAt) từ token
    //    Phục vụ S1-04: so sánh với passwordChangedAt để thu hồi phiên cũ
    public Date getIssuedAtFromToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getIssuedAt();
        } catch (Exception e) {
            return null;
        }
    }


    public boolean validateJwtToken(String authToken) {
        try {
            Jwts.parserBuilder().setSigningKey(key()).build().parse(authToken);
            return true;
        } catch (MalformedJwtException e) {
            System.err.println("Token không đúng định dạng: " + e.getMessage());
        } catch (ExpiredJwtException e) {
            System.err.println("Token đã hết hạn: " + e.getMessage());
        } catch (UnsupportedJwtException e) {
            System.err.println("Token không được hỗ trợ: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.err.println("Chuỗi claims rỗng: " + e.getMessage());
        }
        return false;
    }
}
