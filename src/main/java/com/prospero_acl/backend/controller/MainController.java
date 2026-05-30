package com.prospero_acl.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MainController {

  @GetMapping("/test")
  public String getHello() {
    return "Hello World";
  }

  @PostMapping("/upload")
  public void storeDocument() {

  }

}
