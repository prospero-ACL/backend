package com.prospero_acl.backend.model;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

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
@Table(name = "user_prompts")
@NoArgsConstructor
@AllArgsConstructor
public class UserPrompt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "report_id", nullable = false)
  private Report report;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String text;

  @CreationTimestamp
  @Column(nullable = false, updatable = false)
  private Instant createdAt;
}
