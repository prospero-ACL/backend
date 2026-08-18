package com.prospero_acl.backend.service;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.LlmReply;
import com.prospero_acl.backend.model.Report;
import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.UserPrompt;
import com.prospero_acl.backend.model.dto.ReportCreateDTO;
import com.prospero_acl.backend.model.dto.ReportResponseDTO;
import com.prospero_acl.backend.model.enums.DocumentScope;
import com.prospero_acl.backend.model.enums.ReportStatus;
import com.prospero_acl.backend.model.enums.SecurityLevel;
import com.prospero_acl.backend.repo.LlmReplyRepo;
import com.prospero_acl.backend.repo.ReportRepo;
import com.prospero_acl.backend.repo.UserPromptRepo;
import com.prospero_acl.backend.repo.UserRepo;

@Service
public class ReportService {
  @Autowired
  private UserRepo userRepo;
  @Autowired
  private ReportRepo reportRepo;
  @Autowired
  private UserPromptRepo userPromptRepo;
  @Autowired
  private RAGService ragService;
  @Autowired
  private LlmReplyRepo replyRepo;

  public ReportResponseDTO createReport(String principalId, ReportCreateDTO req) {
    User user = userRepo.findByProviderId(principalId)
        .orElseThrow(() -> new EntityNotFoundException("User not found"));

    Report report = new Report();
    report.setOwner(user);
    report.setStatus(ReportStatus.DRAFT);
    report = reportRepo.save(report);

    UserPrompt prompt = new UserPrompt();
    prompt.setReport(report);
    prompt.setText(req.prompt());
    prompt.setPosition(1);
    userPromptRepo.save(prompt);

    // ACL filter
    List<String> allowedTiers = resolveAllowedTiers(user.getSecurityLevel());
    Filter.Expression filter = buildFilterExpression(allowedTiers, req.scope(), user.getId());

    // RAG call
    String reply = ragService.query(req.prompt(), filter);

    // persist reply
    LlmReply replyEntity = new LlmReply();
    replyEntity.setReport(report);
    replyEntity.setText(reply);
    replyEntity.setPosition(1);
    replyRepo.save(replyEntity);

    return new ReportResponseDTO(report.getId(), reply);
  }

  private Filter.Expression buildFilterExpression(
      List<String> allowedTiers,
      DocumentScope scope,
      UUID userId) {

    FilterExpressionBuilder b = new FilterExpressionBuilder();

    // privacy IN ["public", "restricted", ...]
    FilterExpressionBuilder.Op tierFilter = b.in("privacy", allowedTiers.toArray());

    if (scope == DocumentScope.RESTRICTED) {
      // AND owner == "userId-string"
      FilterExpressionBuilder.Op ownerFilter = b.eq("owner", userId.toString());
      return b.and(tierFilter, ownerFilter).build();
    }

    return tierFilter.build();
  }

  private List<String> resolveAllowedTiers(SecurityLevel level) {
    return switch (level) {
      case LOW -> List.of("public");
      case MEDIUM -> List.of("public", "restricted");
      case HIGH -> List.of("public", "restricted", "elevated");
    };
  }
}
