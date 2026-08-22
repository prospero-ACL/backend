package com.prospero_acl.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.prospero_acl.backend.model.dto.ReportContinueDTO;
import com.prospero_acl.backend.model.dto.ReportCreateDTO;
import com.prospero_acl.backend.model.dto.ReportResponseDTO;
import com.prospero_acl.backend.model.dto.ResponseDocumentDTO;
import com.prospero_acl.backend.model.enums.DocumentScope;
import com.prospero_acl.backend.service.DocumentService;
import com.prospero_acl.backend.service.ReportService;

@RestController
@RequestMapping("/api/v1")
public class MainController {

  @Autowired
  private DocumentService documentService;
  @Autowired
  private ReportService reportService;

  @GetMapping("/test")
  public String getHello() {
    return "Hello World";
  }

  @GetMapping("/documents/{userId}")
  public ResponseEntity<List<ResponseDocumentDTO>> getUserDocs(@PathVariable String userId) {
    // quick guard
    if (!userId.matches("^[a-zA-Z0-9_-]{1,64}$")) {
      throw new IllegalArgumentException("Invalid userId");
    }
    List<ResponseDocumentDTO> responseDocumentDTO = documentService.getDocumentsByUser(userId);
    return ResponseEntity.ok(responseDocumentDTO);
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public void storeDocument(
      @RequestParam("file") MultipartFile file,
      @RequestParam("userId") String userId,
      @RequestParam("scope") DocumentScope scope) {
    documentService.saveDocument(file, userId, scope);
  }

  @PostMapping("/conversations/create")
  public ResponseEntity<ReportResponseDTO> createNewReport(
      @RequestBody ReportCreateDTO reportCreateOptions,
      Authentication authentication) {

    ReportResponseDTO reportResponseDTO = reportService.createReport(authentication.getName(), reportCreateOptions);
    return ResponseEntity.ok(reportResponseDTO);
  }

  @PostMapping("/conversations/{reportId}/continue")
  public ResponseEntity<ReportResponseDTO> continueReport(
      @PathVariable UUID reportId,
      @RequestBody ReportContinueDTO req,
      Authentication authentication) {

    ReportResponseDTO reportResponseDTO = reportService.continueReport(authentication.getName(), reportId, req);
    return ResponseEntity.ok(reportResponseDTO);
  }

  @GetMapping("/conversations/draft")
  public ResponseEntity<ReportResponseDTO> getDraftReport(Authentication authentication) {
    return reportService.getDraftReport(authentication.getName())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/conversations/{reportId}")
  public ResponseEntity<ReportResponseDTO> getReport(
      @PathVariable UUID reportId,
      Authentication authentication) {

    ReportResponseDTO reportResponseDTO = reportService.getReport(authentication.getName(), reportId);
    return ResponseEntity.ok(reportResponseDTO);
  }

}
