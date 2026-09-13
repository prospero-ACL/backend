package com.prospero_acl.backend.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.enums.SecurityLevel;

@Repository
public interface UserRepo extends JpaRepository<User, UUID> {
  Optional<User> findByProviderId(String providerId);

  @Modifying
  @Query("UPDATE User u SET u.securityLevel = :securityLevel WHERE u.providerId = :providerId")
  int updateSecurityLevelByProviderId(
      @Param("providerId") String providerId,
      @Param("securityLevel") SecurityLevel securityLevel);
}
