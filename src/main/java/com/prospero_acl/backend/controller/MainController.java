package com.prospero_acl.backend.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prospero_acl.backend.model.dto.ReportResponseDTO;
import com.prospero_acl.backend.model.dto.RequestDocumentUploadDTO;
import com.prospero_acl.backend.model.dto.ResponseDocumentDto;
import com.prospero_acl.backend.service.DocumentService;

@RestController
@RequestMapping("/api/v1")
public class MainController {

  @Autowired
  DocumentService documentService;

  @GetMapping("/test")
  public String getHello() {
    return "Hello World";
  }

  @GetMapping("/documents/{userId}")
  public ResponseEntity<List<ResponseDocumentDto>> getUserDocs(@PathVariable String userId) {
    // quick guard
    if (!userId.matches("^[a-zA-Z0-9_-]{1,64}$")) {
      throw new IllegalArgumentException("Invalid userId");
    }
    List<ResponseDocumentDto> responseDocumentDto = documentService.getDocumentsByUser(userId);
    return ResponseEntity.ok(responseDocumentDto);
  }

  @PostMapping("/documents")
  public void storeDocument(@RequestBody RequestDocumentUploadDTO req) {

    String text = req.text();
    String fileName = req.name();
    String userId = req.userId();

    documentService.saveDocument(fileName, text, userId);

  }

  @PostMapping("/conversations/create")
  public ReportResponseDTO createNewReport() {

    return new ReportResponseDTO();
  }

}
