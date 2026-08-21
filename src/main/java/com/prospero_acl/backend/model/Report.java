package com.prospero_acl.backend.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.prospero_acl.backend.model.enums.DocumentScope;
import com.prospero_acl.backend.model.enums.ReportStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
public class Report {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "owner_id", nullable = false) // NTS:Name of the FK column name
  private User owner;

  @OneToMany(mappedBy = "report", orphanRemoval = true, cascade = CascadeType.ALL)
  @OrderBy("position ASC")
  private List<UserPrompt> prompts = new ArrayList<>();

  @OneToMany(mappedBy = "report", orphanRemoval = true, cascade = CascadeType.ALL)
  @OrderBy("position ASC")
  private List<LlmReply> replies = new ArrayList<>();

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private ReportStatus status = ReportStatus.DRAFT;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private DocumentScope scope;

  @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  private Set<ReportChunk> chunks = new HashSet<>();

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false, updatable = true)
  private Instant updatedAt;
}
