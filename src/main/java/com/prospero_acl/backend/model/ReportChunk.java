package com.prospero_acl.backend.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Data
@Entity
@Table(name = "report_chunks")
@AllArgsConstructor
@NoArgsConstructor
public class ReportChunk {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // FK into the reports table
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "report_id", nullable = false)
  private Report report;

  // Just a UUID — Hibernate does NOT manage a FK into vector_store
  // the problem is that the pgvector_store is not a JPA entity
  @Column(name = "chunk_id", nullable = false)
  private UUID chunkId;
}
