package com.prospero_acl.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prospero_acl.backend.model.dto.ResponseUserDTO;
import com.prospero_acl.backend.service.UserService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

  @Autowired
  private UserService userService;

  @GetMapping("/me")
  public ResponseEntity<ResponseUserDTO> getUserMe(Authentication authentication) {
    String providerId = authentication.getName(); // JWT filter sets providerId as principal

    return userService.findByProviderId(providerId)
        .map(user -> new ResponseUserDTO(
            user.getId().toString(),
            user.getEmail(),
            user.getTheme(),
            user.getName(),
            user.getAvatarUrl()))
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletResponse response, Authentication authentication) {
    authentication = null;

    Cookie cookie = new Cookie("access_token", "");
    cookie.setHttpOnly(true);
    cookie.setPath("/");
    cookie.setMaxAge(0);

    response.addCookie(cookie);

    return ResponseEntity.ok().build();
  }

}
