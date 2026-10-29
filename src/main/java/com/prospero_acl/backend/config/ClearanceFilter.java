package com.prospero_acl.backend.config;

import java.io.IOException;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.prospero_acl.backend.service.UserService;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Resolves the caller's clearance once per request so corpus queries run as the matching Postgres
 * role. Deliberately not a {@code @Component}: Spring Boot would then also register it outside the
 * security chain, where the authentication is not yet set, and OncePerRequestFilter would suppress
 * the in-chain invocation that actually has one.
 */
public class ClearanceFilter extends OncePerRequestFilter {

  private final UserService userService;

  public ClearanceFilter(UserService userService) {
    this.userService = userService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      try {
        ClearanceContext.set(userService.getSecurityLevel(auth.getName()));
      } catch (EntityNotFoundException ignored) {
        // leave unset: the routing DataSource refuses to open a corpus connection without a clearance
      }
    }

    try {
      filterChain.doFilter(request, response);
    } finally {
      ClearanceContext.clear();
    }
  }
}
