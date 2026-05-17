package com.prospero_acl.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prospero_acl.backend.model.dto.ExtractedUserDTO;
import com.prospero_acl.backend.model.dto.ResponseUserDTO;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

  @GetMapping("/me")
  public ResponseEntity<ResponseUserDTO> getUserMe(Authentication authentication) {
    if (authentication == null ||
        !authentication.isAuthenticated() ||
        authentication instanceof AnonymousAuthenticationToken) {
      System.out.println("No authentication");
      return ResponseEntity.status(401).build();
    }

    Object principal = authentication.getPrincipal();
    String email = null;
    String name = null;

    // if (principal instanceof OAuth2User oauth2User) {
    // // Active OAuth2 login session
    // email = oauth2User.getAttribute("email");
    // name = oauth2User.getAttribute("name");
    // } else {
    // return ResponseEntity.status(401).build();
    // }
    //
    email = (email != null) ? email : "Empty is email";
    name = (name != null) ? name : "Empty is name";

    System.out.println("returning user:\n" + email + "\n" + name);
    return ResponseEntity.ok(new ResponseUserDTO(email, name));
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
