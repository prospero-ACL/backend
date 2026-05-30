package com.prospero_acl.backend.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.dto.ExtractedUserDTO;
import com.prospero_acl.backend.repo.UserRepo;

@Service
public class UserService {

  @Autowired
  private UserRepo userRepo;

  public Optional<User> findByProviderId(String providerId) {
    return userRepo.findByProviderId(providerId);
  }

  public User findOrCreateUser(ExtractedUserDTO exUser) {
    return userRepo.findByProviderId(exUser.providerId())
        .orElseGet(() -> createUser(exUser));
  }

  public User createUser(ExtractedUserDTO exUser) {
    User user = new User();
    user.setProviderId(exUser.providerId());
    user.setProvider(exUser.provider());
    user.setEmail(exUser.email());
    user.setName(exUser.name());
    user.setAvatarUrl(exUser.avatarUrl());
    userRepo.save(user);
    return user;
  }

}
