package com.prospero_acl.backend.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.prospero_acl.backend.model.User;

@Repository
public interface UserRepo extends JpaRepository<User, UUID> {
  User findByProviderId(String providerId);

}
