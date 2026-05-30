package com.prospero_acl.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.prospero_acl.backend.model.dto.RequestDocumentUploadDTO;
import com.prospero_acl.backend.repo.DocumentService;

@RestController
@RequestMapping("/api/v1")
public class MainController {

  @Autowired
  DocumentService documentService;

  @GetMapping("/test")
  public String getHello() {
    return "Hello World";
  }

  @PostMapping("/upload")
  public void storeDocument(@RequestBody RequestDocumentUploadDTO req) {

    String text = req.text();
    documentService.saveDocument(text);

  }

}
