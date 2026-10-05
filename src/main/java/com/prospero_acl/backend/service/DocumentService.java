package com.prospero_acl.backend.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.IngestionJob;
import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.dto.BookUploadDTO;
import com.prospero_acl.backend.model.dto.IngestionJobDTO;
import com.prospero_acl.backend.repo.IngestionJobRepo;

import jakarta.persistence.EntityNotFoundException;

@Service
public class DocumentService {

  private static final int BOOKS_PER_TRILOGY = 3;

  private final IngestionJobRepo jobRepo;
  private final TrilogyIngestionWorker worker;
  // Deliberately the application connection, not the clearance-routed one: trilogy titles are
  // public to every tier, only the chunks behind them are gated.
  private final JdbcTemplate appJdbcTemplate;

  public DocumentService(
      IngestionJobRepo jobRepo,
      TrilogyIngestionWorker worker,
      @Qualifier("appJdbcTemplate") JdbcTemplate appJdbcTemplate) {
    this.jobRepo = jobRepo;
    this.worker = worker;
    this.appJdbcTemplate = appJdbcTemplate;
  }

  public IngestionJobDTO startIngestion(User owner, String trilogy, List<BookUploadDTO> books) {
    validate(trilogy, books);

    IngestionJob job = new IngestionJob();
    job.setTrilogy(trilogy.trim());
    job.setOwner(owner);
    job.setBooksTotal(books.size());
    job = jobRepo.save(job);

    worker.ingest(job.getId(), job.getTrilogy(), owner.getId().toString(), books);
    return toDTO(job);
  }

  public IngestionJobDTO getIngestion(UUID jobId, UUID ownerId) {
    return jobRepo.findByIdAndOwner_Id(jobId, ownerId)
        .map(DocumentService::toDTO)
        .orElseThrow(() -> new EntityNotFoundException("Ingestion job not found"));
  }

  public List<String> listTrilogies() {
    return appJdbcTemplate.queryForList(
        "SELECT DISTINCT metadata ->> 'trilogy' FROM vector_store "
            + "WHERE metadata ->> 'trilogy' IS NOT NULL ORDER BY 1",
        String.class);
  }

  private void validate(String trilogy, List<BookUploadDTO> books) {
    if (trilogy == null || trilogy.isBlank()) {
      throw new IllegalArgumentException("A trilogy title is required");
    }
    if (books.size() != BOOKS_PER_TRILOGY) {
      throw new IllegalArgumentException("A trilogy needs exactly " + BOOKS_PER_TRILOGY + " books");
    }
    Set<Integer> positions = books.stream().map(BookUploadDTO::position).collect(Collectors.toSet());
    if (!positions.equals(Set.of(1, 2, 3))) {
      throw new IllegalArgumentException("Book positions must be 1, 2 and 3, each used once");
    }
  }

  private static IngestionJobDTO toDTO(IngestionJob job) {
    return new IngestionJobDTO(
        job.getId(),
        job.getTrilogy(),
        job.getStatus(),
        job.getBooksDone(),
        job.getBooksTotal(),
        job.getChunksWritten(),
        job.getError());
  }
}
