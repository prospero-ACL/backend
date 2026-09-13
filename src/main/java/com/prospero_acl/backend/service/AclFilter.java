package com.prospero_acl.backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.prospero_acl.backend.model.enums.SecurityLevel;

/**
 * Canonical document-visibility rule for a given SecurityLevel, per the ACL
 * table in README.md. Shared by ReportService (RAG retrieval) and
 * DocumentService (document listing/selection) so the matrix is defined
 * exactly once.
 */
public final class AclFilter {

  private AclFilter() {
  }

  public static String visibilityClause(SecurityLevel level, UUID userId) {
    String owner = userId.toString();
    List<String> clauses = new ArrayList<>();
    clauses.add("privacy == 'public'");
    switch (level) {
      case PLEBIAN -> clauses.add("(privacy == 'restricted' && owner == '" + owner + "')");
      case EQUES -> {
        clauses.add("privacy == 'restricted'");
        clauses.add("(privacy == 'elevated' && owner == '" + owner + "')");
      }
      case PATRICIAN -> {
        clauses.add("privacy == 'restricted'");
        clauses.add("privacy == 'elevated'");
      }
    }
    return "(" + String.join(" || ", clauses) + ")";
  }
}
