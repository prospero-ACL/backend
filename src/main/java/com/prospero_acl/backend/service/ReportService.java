package com.prospero_acl.backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prospero_acl.backend.exception.ReportCompletedException;
import com.prospero_acl.backend.model.LlmReply;
import com.prospero_acl.backend.model.Report;
import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.UserPrompt;
import com.prospero_acl.backend.model.dto.ReportContinueDTO;
import com.prospero_acl.backend.model.dto.ReportCreateDTO;
import com.prospero_acl.backend.model.dto.ReportResponseDTO;
import com.prospero_acl.backend.model.dto.ReportTurnDTO;
import com.prospero_acl.backend.model.enums.DocumentScope;
import com.prospero_acl.backend.model.enums.ReportStatus;
import com.prospero_acl.backend.model.enums.SecurityLevel;
import com.prospero_acl.backend.repo.LlmReplyRepo;
import com.prospero_acl.backend.repo.ReportRepo;
import com.prospero_acl.backend.repo.UserPromptRepo;
import com.prospero_acl.backend.repo.UserRepo;

@Service
@Transactional
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
    report.setScope(req.scope());
    report = reportRepo.save(report);

    int position = nextPosition(report);

    UserPrompt prompt = new UserPrompt();
    prompt.setReport(report);
    prompt.setText(req.prompt());
    prompt.setPosition(position);
    userPromptRepo.save(prompt);
    report.getPrompts().add(prompt);

    List<String> allowedTiers = resolveAllowedTiers(user.getSecurityLevel());
    String filter = buildFilterExpression(allowedTiers, report.getScope(), user.getId());

    // TODO: Add predefiend instructions to the agent to ensure it answers the
    // question in a concise and accurate manner, and that it cites the source
    // documents used to answer the question.
    String reply = ragService.query(req.prompt(), List.of(), filter);

    LlmReply replyEntity = new LlmReply();
    replyEntity.setReport(report);
    replyEntity.setText(reply);
    replyEntity.setPosition(position);
    replyRepo.save(replyEntity);
    report.getReplies().add(replyEntity);

    report.setStatus(statusForPosition(position));
    report = reportRepo.save(report);

    return toResponseDTO(report);
  }

  public ReportResponseDTO continueReport(String principalId, UUID reportId, ReportContinueDTO req) {
    User user = userRepo.findByProviderId(principalId)
        .orElseThrow(() -> new EntityNotFoundException("User not found"));

    Report report = reportRepo.findByIdAndOwner_Id(reportId, user.getId())
        .orElseThrow(() -> new EntityNotFoundException("Report not found"));

    if (report.getStatus() == ReportStatus.COMPLETED) {
      throw new ReportCompletedException("Report has reached its 3-question limit");
    }

    List<Message> history = buildHistory(report);
    int position = nextPosition(report);

    UserPrompt prompt = new UserPrompt();
    prompt.setReport(report);
    prompt.setText(req.prompt());
    prompt.setPosition(position);
    userPromptRepo.save(prompt);
    report.getPrompts().add(prompt);

    List<String> allowedTiers = resolveAllowedTiers(user.getSecurityLevel());
    String filter = buildFilterExpression(allowedTiers, report.getScope(), user.getId());

    String replyText = ragService.query(req.prompt(), history, filter);

    LlmReply replyEntity = new LlmReply();
    replyEntity.setReport(report);
    replyEntity.setText(replyText);
    replyEntity.setPosition(position);
    replyRepo.save(replyEntity);
    report.getReplies().add(replyEntity);

    report.setStatus(statusForPosition(position));
    report = reportRepo.save(report);

    return toResponseDTO(report);
  }

  @Transactional(readOnly = true)
  public ReportResponseDTO getReport(String principalId, UUID reportId) {
    User user = userRepo.findByProviderId(principalId)
        .orElseThrow(() -> new EntityNotFoundException("User not found"));

    Report report = reportRepo.findByIdAndOwner_Id(reportId, user.getId())
        .orElseThrow(() -> new EntityNotFoundException("Report not found"));

    return toResponseDTO(report);
  }

  @Transactional(readOnly = true)
  public Optional<ReportResponseDTO> getDraftReport(String principalId) {
    User user = userRepo.findByProviderId(principalId)
        .orElseThrow(() -> new EntityNotFoundException("User not found"));

    return reportRepo
        .findFirstByOwner_IdAndStatusInOrderByCreatedAtDesc(
            user.getId(), List.of(ReportStatus.DRAFT, ReportStatus.IN_PROGRESS))
        .map(this::toResponseDTO);
  }

  private int nextPosition(Report report) {
    return report.getPrompts().size() + 1;
  }

  private ReportStatus statusForPosition(int position) {
    if (position == 1) {
      return ReportStatus.DRAFT;
    } else if (position < 3) {
      return ReportStatus.IN_PROGRESS;
    } else {
      return ReportStatus.COMPLETED;
    }
  }

  private List<Message> buildHistory(Report report) {
    List<Message> messages = new ArrayList<>();
    List<UserPrompt> prompts = report.getPrompts();
    List<LlmReply> replies = report.getReplies();
    for (int i = 0; i < prompts.size(); i++) {
      messages.add(new UserMessage(prompts.get(i).getText()));
      messages.add(new AssistantMessage(replies.get(i).getText()));
    }
    return messages;
  }

  private ReportResponseDTO toResponseDTO(Report report) {
    List<UserPrompt> prompts = report.getPrompts();
    List<LlmReply> replies = report.getReplies();
    List<ReportTurnDTO> turns = new ArrayList<>();
    for (int i = 0; i < prompts.size(); i++) {
      turns.add(new ReportTurnDTO(
          prompts.get(i).getPosition(),
          prompts.get(i).getText(),
          replies.get(i).getText()));
    }
    return new ReportResponseDTO(report.getId(), report.getStatus(), turns);
  }

  private String buildFilterExpression(
      List<String> allowedTiers,
      DocumentScope scope,
      UUID userId) {

    // QuestionAnswerAdvisor.FILTER_EXPRESSION is parsed as filter-expression DSL
    // text
    // (FilterExpressionTextParser), not built from a Filter.Expression object.
    String tiers = allowedTiers.stream()
        .map(tier -> "'" + tier + "'")
        .collect(Collectors.joining(", "));
    String filterExpression = "privacy in [" + tiers + "]";

    if (scope == DocumentScope.RESTRICTED) {
      filterExpression += " && owner == '" + userId + "'";
    }

    return filterExpression;
  }

  private List<String> resolveAllowedTiers(SecurityLevel level) {
    return switch (level) {
      case LOW -> List.of("public");
      case MEDIUM -> List.of("public", "restricted");
      case HIGH -> List.of("public", "restricted", "elevated");
    };
  }
}
