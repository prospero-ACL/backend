package com.prospero_acl.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prospero_acl.backend.model.Conversation;
import com.prospero_acl.backend.model.LlmReply;
import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.UserPrompt;
import com.prospero_acl.backend.model.dto.ConversationContinueDTO;
import com.prospero_acl.backend.model.dto.ConversationCreateDTO;
import com.prospero_acl.backend.model.dto.ConversationResponseDTO;
import com.prospero_acl.backend.model.dto.ConversationTurnDTO;
import com.prospero_acl.backend.repo.ConversationRepo;
import com.prospero_acl.backend.repo.LlmReplyRepo;
import com.prospero_acl.backend.repo.UserPromptRepo;
import com.prospero_acl.backend.repo.UserRepo;

@Service
@Transactional
public class ConversationService {
  @Autowired
  private UserRepo userRepo;
  @Autowired
  private ConversationRepo conversationRepo;
  @Autowired
  private UserPromptRepo userPromptRepo;
  @Autowired
  private RAGService ragService;
  @Autowired
  private LlmReplyRepo replyRepo;

  // Conversations are unbounded, so only the most recent turns are replayed to the model.
  @Value("${app.conversation.history-turns}")
  private int historyTurns;

  public ConversationResponseDTO createConversation(String principalId, ConversationCreateDTO req) {
    User user = resolveUser(principalId);

    Conversation conversation = new Conversation();
    conversation.setOwner(user);
    conversation = conversationRepo.save(conversation);

    // TODO: Add predefiend instructions to the agent to ensure it answers the
    // question in a concise and accurate manner, and that it cites the source
    // documents used to answer the question.
    addTurn(conversation, req.prompt(), List.of());

    return toResponseDTO(conversationRepo.save(conversation));
  }

  public ConversationResponseDTO continueConversation(
      String principalId, UUID conversationId, ConversationContinueDTO req) {
    Conversation conversation = findOwned(principalId, conversationId);

    addTurn(conversation, req.prompt(), buildHistory(conversation));

    return toResponseDTO(conversationRepo.save(conversation));
  }

  @Transactional(readOnly = true)
  public ConversationResponseDTO getConversation(String principalId, UUID conversationId) {
    return toResponseDTO(findOwned(principalId, conversationId));
  }

  @Transactional(readOnly = true)
  public Optional<ConversationResponseDTO> getLatestConversation(String principalId) {
    User user = resolveUser(principalId);
    return conversationRepo.findFirstByOwner_IdOrderByUpdatedAtDesc(user.getId())
        .map(this::toResponseDTO);
  }

  private User resolveUser(String principalId) {
    return userRepo.findByProviderId(principalId)
        .orElseThrow(() -> new EntityNotFoundException("User not found"));
  }

  private Conversation findOwned(String principalId, UUID conversationId) {
    User user = resolveUser(principalId);
    return conversationRepo.findByIdAndOwner_Id(conversationId, user.getId())
        .orElseThrow(() -> new EntityNotFoundException("Conversation not found"));
  }

  private void addTurn(Conversation conversation, String text, List<Message> history) {
    int position = nextPosition(conversation);

    UserPrompt prompt = new UserPrompt();
    prompt.setConversation(conversation);
    prompt.setText(text);
    prompt.setPosition(position);
    userPromptRepo.save(prompt);
    conversation.getPrompts().add(prompt);

    String replyText = ragService.query(text, history);

    LlmReply reply = new LlmReply();
    reply.setConversation(conversation);
    reply.setText(replyText);
    reply.setPosition(position);
    replyRepo.save(reply);
    conversation.getReplies().add(reply);

    // Turns live in other tables, so nothing on the conversation row changes by itself;
    // without this @UpdateTimestamp never fires and getLatestConversation goes stale.
    conversation.setUpdatedAt(Instant.now());
  }

  // Derived from the highest position rather than the list size, so a gap never produces a
  // duplicate position.
  private int nextPosition(Conversation conversation) {
    return conversation.getPrompts().stream()
        .mapToInt(UserPrompt::getPosition)
        .max()
        .orElse(0) + 1;
  }

  // Replayed history only contains turns that were actually answered, newest last.
  private List<Message> buildHistory(Conversation conversation) {
    Map<Integer, LlmReply> replies = repliesByPosition(conversation);
    List<UserPrompt> answered = conversation.getPrompts().stream()
        .filter(p -> replies.containsKey(p.getPosition()))
        .toList();

    List<Message> messages = new ArrayList<>();
    for (UserPrompt prompt : answered.subList(Math.max(0, answered.size() - historyTurns), answered.size())) {
      messages.add(new UserMessage(prompt.getText()));
      messages.add(new AssistantMessage(replies.get(prompt.getPosition()).getText()));
    }
    return messages;
  }

  // Prompts and replies are paired by position, not by list index, so the two lists drifting
  // apart cannot misattribute a reply or throw.
  private ConversationResponseDTO toResponseDTO(Conversation conversation) {
    Map<Integer, LlmReply> replies = repliesByPosition(conversation);
    List<ConversationTurnDTO> turns = conversation.getPrompts().stream()
        .map(p -> {
          LlmReply reply = replies.get(p.getPosition());
          return new ConversationTurnDTO(p.getPosition(), p.getText(), reply == null ? null : reply.getText());
        })
        .toList();
    return new ConversationResponseDTO(conversation.getId(), turns);
  }

  private Map<Integer, LlmReply> repliesByPosition(Conversation conversation) {
    return conversation.getReplies().stream()
        .collect(Collectors.toMap(LlmReply::getPosition, Function.identity()));
  }
}
