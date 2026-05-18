package com.prospero_acl.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.repo.UserRepo;

@Service
public class UserService {

  @Autowired
  private UserRepo userRepo;

  public User findByProviderId(String providerId) {
    return userRepo.findByProviderId(providerId);
  }

}
