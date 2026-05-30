package com.prospero_acl.backend.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity(name = "users")
@AllArgsConstructor
@NoArgsConstructor
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(unique = true, nullable = false)
  private String providerId;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = true)
  private String name;

  @Column
  private String email;

  @Column(nullable = true)
  private String avatarUrl;

  @Column
  private String theme = "auto";

  @Column(nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false, updatable = true)
  private Instant updatedAt = Instant.now();
}
