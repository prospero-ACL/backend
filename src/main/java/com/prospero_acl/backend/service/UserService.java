package com.prospero_acl.backend.service;

import java.util.Optional;

import jakarta.persistence.EntityNotFoundException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.dto.ExtractedUserDTO;
import com.prospero_acl.backend.model.enums.SecurityLevel;
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

  public SecurityLevel getSecurityLevel(String providerId) {
    return userRepo.findByProviderId(providerId)
        .orElseThrow(() -> new EntityNotFoundException("User not found"))
        .getSecurityLevel();
  }

  @Transactional
  public void updateSecurityLevel(String providerId, SecurityLevel securityLevel) {
    int updated = userRepo.updateSecurityLevelByProviderId(providerId, securityLevel);
    if (updated == 0) {
      throw new EntityNotFoundException("User not found");
    }
  }

}
