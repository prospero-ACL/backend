package com.prospero_acl.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prospero_acl.backend.model.dto.UserDTO;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

  @GetMapping("/me")
  public ResponseEntity<UserDTO> getUserMe(Authentication authentication) {
    String name = authentication.getName();
    String email = authentication.getPrincipal().toString();
    UserDTO user = new UserDTO(email, name);
    return ResponseEntity.ok(user);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletResponse response) {

    Cookie cookie = new Cookie("access_token", "");

    cookie.setHttpOnly(true);
    cookie.setSecure(false);
    cookie.setPath("/");
    cookie.setMaxAge(0);

    response.addCookie(cookie);

    return ResponseEntity.ok().build();
  }

}
