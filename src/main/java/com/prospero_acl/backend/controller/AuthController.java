package com.prospero_acl.backend.controller;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.dto.ExtractedUserDTO;
import com.prospero_acl.backend.model.dto.ResponseUserDTO;
import com.prospero_acl.backend.service.ExtractedUserInfoFactory;
import com.prospero_acl.backend.service.UserService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

  @Autowired
  private UserService userService;

  @Autowired
  private ExtractedUserInfoFactory extractedUserInfoFactory;

  @GetMapping("/me")
  public ResponseEntity<ResponseUserDTO> getUserMe(Authentication authentication) {
    OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
    String registrationId = token.getAuthorizedClientRegistrationId();
    OAuth2User oAuth2User = token.getPrincipal();
    ExtractedUserDTO exUser = extractedUserInfoFactory.create(registrationId, oAuth2User);

    User storedUser = userService.findByProviderId(exUser.providerId());

    if (storedUser == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
    }

    return ResponseEntity.ok(new ResponseUserDTO(storedUser.getId().toString(),
        storedUser.getEmail(),
        storedUser.getTheme(),
        storedUser.getName(),
        storedUser.getAvatarUrl()));
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
