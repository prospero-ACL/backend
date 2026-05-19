package com.prospero_acl.backend.service;

import java.util.Map;

import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.dto.ExtractedUserDTO;

@Service
public class ExtractedUserInfoFactory {

  public ExtractedUserDTO create(String registrationId, OAuth2User oAuth2User) {
    return switch (registrationId.toLowerCase()) {
      case "github" -> extractGithub(oAuth2User);
      case "google" -> extractGoogle(oAuth2User);

      default -> throw new IllegalArgumentException("Invalid registrationId: " + registrationId);
    };
  }

  private ExtractedUserDTO extractGithub(OAuth2User oAuth2User) {
    Map<String, Object> attrs = oAuth2User.getAttributes();
    String id = String.valueOf(attrs.get("id"));
    String email = (String) attrs.getOrDefault("email", "Unknown"); // null when user set email to private
    String name = (String) attrs.getOrDefault("name",
        attrs.getOrDefault("login", "Unknown")); // name can be null, login never is

    String avatarUrl = (String) attrs.get("avatar_url"); // is this always present?
    return new ExtractedUserDTO(id, email, name, avatarUrl);
  }

  private ExtractedUserDTO extractGoogle(OAuth2User oAuth2User) {
    Map<String, Object> attrs = oAuth2User.getAttributes();
    String id = (String) attrs.get("sub");
    String email = (String) attrs.get("email"); // always present for Google
    String name = (String) attrs.get("name");
    String avatarUrl = (String) attrs.get("picture");
    return new ExtractedUserDTO(id, email, name, avatarUrl);
  }
}
