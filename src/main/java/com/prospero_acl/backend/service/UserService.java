package com.prospero_acl.backend.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.repo.UserRepo;

@Service
public class UserService {

  @Autowired
  private UserRepo userRepo;

  public Optional<User> getUser(String providerId) {
    return userRepo.findByProviderId(providerId);
  }

}
