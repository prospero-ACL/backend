package com.prospero_acl.backend.service;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

  @Value("${jwt.secret}")
  private String SECRET;

  public String generateToken(OAuth2User user) {

    SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    String email = user.getAttribute("email");

    return Jwts.builder()
        .subject(email)
        .issuedAt(new Date())
        .expiration(
            new Date(System.currentTimeMillis() + 1000 * 60 * 60))
        .signWith(key)
        .compact();
  }
}
