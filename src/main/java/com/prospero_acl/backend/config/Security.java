package com.prospero_acl.backend.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.prospero_acl.backend.service.JwtService;

import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class Security {
  @Value("${app.frontend-url}")
  private String frontendUrl;

  @Autowired
  private JwtAuthFilter jwtAuthFilter;

  private final JwtService jwtService;

  @Bean
  public SecurityFilterChain defaultSilterChain(HttpSecurity http) throws Exception {
    return http
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(csrf -> csrf.disable())// For production
        .authorizeHttpRequests(req -> req
            // exept register that is un protected
            .requestMatchers("/oauth2/**").permitAll()
            .anyRequest().authenticated())

        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

        .oauth2Login(oauth2 -> oauth2
            .successHandler(oAuth2SuccessHandler())
            .failureHandler(authenticationFailureHandler()))
        .build();

  }

  @Bean
  public AuthenticationSuccessHandler oAuth2SuccessHandler() {
    return (request, response, authentication) -> {
      OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

      oauth2User.getAttributes().forEach((key, value) -> System.out.println(key + ": " + value));
      // Create JWT or session token
      String token = jwtService.generateToken(oauth2User);
      Cookie cookie = new Cookie("access_token", token);
      cookie.setHttpOnly(true);
      cookie.setSecure(false);
      cookie.setPath("/");
      cookie.setMaxAge(60 * 60);

      response.addCookie(cookie);
      // Redirect to frontend with token
      response.sendRedirect(frontendUrl);
    };
  }

  @Bean
  public AuthenticationFailureHandler authenticationFailureHandler() {
    return (request, response, exception) -> {
      response.sendRedirect(frontendUrl + "/?error=oauth_failed");
    };
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(frontendUrl));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

}
