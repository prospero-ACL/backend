package com.prospero_acl.backend.service;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.dto.ExtractedUserDTO;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

  @Value("${jwt.secret}")
  private String SECRET;

  public String generateToken(ExtractedUserDTO user) {

    SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    String subject = user.providerId();

    return Jwts.builder()
        .subject(subject)
        .issuedAt(new Date())
        .expiration(
            new Date(System.currentTimeMillis() + 1000 * 60 * 60))
        .signWith(key)
        .compact();
  }

  public boolean isValid(String token) {
    try {
      SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

      Jwts.parser()
          .verifyWith(key)
          .build()
          .parseSignedClaims(token);

      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public String extractProviderId(String token) {
    SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    return Jwts.parser()
        .verifyWith(key)
        .build()
        .parseSignedClaims(token)
        .getPayload()
        .getSubject();
  }
}
