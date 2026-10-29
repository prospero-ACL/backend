package com.prospero_acl.backend.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.prospero_acl.backend.model.enums.IngestionStatus;

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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tracks one trilogy upload. Embedding three novels takes minutes, so the HTTP request only starts
 * the work and hands back this row's id for the client to poll.
 */
@Data
@Entity
@Table(name = "ingestion_job")
@AllArgsConstructor
@NoArgsConstructor
public class IngestionJob {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String trilogy;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private IngestionStatus status = IngestionStatus.PENDING;

  @Column(nullable = false)
  private Integer booksTotal;

  @Column(nullable = false)
  private Integer booksDone = 0;

  @Column(nullable = false)
  private Integer chunksWritten = 0;

  @Column(columnDefinition = "TEXT")
  private String error;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "owner_id", nullable = false)
  private User owner;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(nullable = false)
  private Instant updatedAt;
}
