package com.prospero_acl.backend.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
public class Conversation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "owner_id", nullable = false) // NTS:Name of the FK column name
  private User owner;

  // Prompts and replies both point back here, so they are excluded from toString/equals to keep
  // Lombok from recursing between the two sides.
  @OneToMany(mappedBy = "conversation", orphanRemoval = true, cascade = CascadeType.ALL)
  @OrderBy("position ASC")
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private List<UserPrompt> prompts = new ArrayList<>();

  @OneToMany(mappedBy = "conversation", orphanRemoval = true, cascade = CascadeType.ALL)
  @OrderBy("position ASC")
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private List<LlmReply> replies = new ArrayList<>();

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false, updatable = true)
  private Instant updatedAt;
}
