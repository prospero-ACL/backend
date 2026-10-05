package com.prospero_acl.backend.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.prospero_acl.backend.exception.InsufficientClearanceException;
import com.prospero_acl.backend.exception.UnreadablePdfException;
import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.dto.BookUploadDTO;
import com.prospero_acl.backend.model.dto.ConversationContinueDTO;
import com.prospero_acl.backend.model.dto.ConversationCreateDTO;
import com.prospero_acl.backend.model.dto.ConversationResponseDTO;
import com.prospero_acl.backend.model.dto.IngestionJobDTO;
import com.prospero_acl.backend.model.enums.SecurityLevel;
import com.prospero_acl.backend.model.dto.SecurityLevelDTO;
import com.prospero_acl.backend.service.ConversationService;
import com.prospero_acl.backend.service.DocumentService;
import com.prospero_acl.backend.service.UserService;

@RestController
@RequestMapping("/api/v1")
public class MainController {

  @Autowired
  private DocumentService documentService;
  @Autowired
  private ConversationService conversationService;
  @Autowired
  private UserService userService;

  @GetMapping("/test")
  public String getHello() {
    return "Hello World";
  }

  @GetMapping("/documents")
  public ResponseEntity<List<String>> getTrilogies() {
    return ResponseEntity.ok(documentService.listTrilogies());
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public IngestionJobDTO storeTrilogy(
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam("positions") List<Integer> positions,
      @RequestParam("trilogyName") String trilogyName,
      Authentication authentication) {

    User user = resolveUser(authentication);
    if (user.getSecurityLevel() != SecurityLevel.PATRICIAN) {
      throw new InsufficientClearanceException("Only patricians may upload a trilogy");
    }
    if (files.size() != positions.size()) {
      throw new IllegalArgumentException("Each file needs exactly one book position");
    }

    List<BookUploadDTO> books = new ArrayList<>();
    for (int i = 0; i < files.size(); i++) {
      MultipartFile file = files.get(i);
      try {
        books.add(new BookUploadDTO(positions.get(i), file.getOriginalFilename(), file.getBytes()));
      } catch (IOException e) {
        throw new UnreadablePdfException("Could not read \"" + file.getOriginalFilename() + "\"", e);
      }
    }
    return documentService.startIngestion(user, trilogyName, books);
  }

  @GetMapping("/documents/ingestions/{jobId}")
  public IngestionJobDTO getIngestion(@PathVariable UUID jobId, Authentication authentication) {
    return documentService.getIngestion(jobId, resolveUser(authentication).getId());
  }

  private User resolveUser(Authentication authentication) {
    return userService.findByProviderId(authentication.getName())
        .orElseThrow(() -> new EntityNotFoundException("User not found"));
  }

  @GetMapping("/me/security-level")
  public ResponseEntity<SecurityLevelDTO> getSecurityLevel(Authentication authentication) {
    SecurityLevelDTO securityLevelDTO = new SecurityLevelDTO(
        userService.getSecurityLevel(authentication.getName()));
    return ResponseEntity.ok(securityLevelDTO);
  }

  @PostMapping("/me/security-level")
  public ResponseEntity<Void> updateSecurityLevel(
      @RequestBody SecurityLevelDTO req,
      Authentication authentication) {
    userService.updateSecurityLevel(authentication.getName(), req.securityLevel());
    return ResponseEntity.ok().build();
  }

  @PostMapping("/conversations/create")
  public ResponseEntity<ConversationResponseDTO> createConversation(
      @RequestBody ConversationCreateDTO req,
      Authentication authentication) {

    return ResponseEntity.ok(conversationService.createConversation(authentication.getName(), req));
  }

  @PostMapping("/conversations/{conversationId}/continue")
  public ResponseEntity<ConversationResponseDTO> continueConversation(
      @PathVariable UUID conversationId,
      @RequestBody ConversationContinueDTO req,
      Authentication authentication) {

    return ResponseEntity.ok(
        conversationService.continueConversation(authentication.getName(), conversationId, req));
  }

  @GetMapping("/conversations/latest")
  public ResponseEntity<ConversationResponseDTO> getLatestConversation(Authentication authentication) {
    return conversationService.getLatestConversation(authentication.getName())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/conversations/{conversationId}")
  public ResponseEntity<ConversationResponseDTO> getConversation(
      @PathVariable UUID conversationId,
      Authentication authentication) {

    return ResponseEntity.ok(conversationService.getConversation(authentication.getName(), conversationId));
  }

}
