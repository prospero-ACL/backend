package com.prospero_acl.backend.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/api/v1")
public class MainController {

  @RequestMapping("/")
  public String index() {
    return "index";
  }

}
