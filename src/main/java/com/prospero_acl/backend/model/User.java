package com.prospero_acl.backend.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.prospero_acl.backend.model.enums.SecurityLevel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "users")
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

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private SecurityLevel securityLevel = SecurityLevel.LOW;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false, updatable = true)
  private Instant updatedAt;

  // NTS: mappeBy points to @JoinColumn having field
  @OneToMany(mappedBy = "owner", orphanRemoval = true)
  Set<Report> reports = new HashSet<>();
}
