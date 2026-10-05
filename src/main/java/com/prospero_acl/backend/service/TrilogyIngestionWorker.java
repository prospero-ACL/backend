package com.prospero_acl.backend.service;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.prospero_acl.backend.config.ClearanceContext;
import com.prospero_acl.backend.model.IngestionJob;
import com.prospero_acl.backend.model.dto.BookUploadDTO;
import com.prospero_acl.backend.model.enums.IngestionStatus;
import com.prospero_acl.backend.model.enums.SecurityLevel;
import com.prospero_acl.backend.repo.IngestionJobRepo;

/**
 * Runs a trilogy upload off the request thread: three novels are on the order of a thousand chunks
 * and take minutes to embed.
 */
@Component
public class TrilogyIngestionWorker {

  private final VectorStore vectorStore;
  private final IngestionJobRepo jobRepo;
  private final JdbcTemplate appJdbcTemplate;

  public TrilogyIngestionWorker(
      VectorStore vectorStore,
      IngestionJobRepo jobRepo,
      @Qualifier("appJdbcTemplate") JdbcTemplate appJdbcTemplate) {
    this.vectorStore = vectorStore;
    this.jobRepo = jobRepo;
    this.appJdbcTemplate = appJdbcTemplate;
  }

  @Async
  public void ingest(UUID jobId, String trilogy, String ownerId, List<BookUploadDTO> books) {
    // There is no request on this thread, so the clearance the corpus DataSource routes on has to be
    // set deliberately. Only a patrician can reach this code (checked in MainController) and only
    // postgres_patrician holds INSERT on vector_store, so the elevation is bounded at both ends.
    ClearanceContext.set(SecurityLevel.PATRICIAN);
    try {
      IngestionJob job = jobRepo.findById(jobId).orElseThrow();
      job.setStatus(IngestionStatus.RUNNING);
      jobRepo.save(job);

      // Re-uploading a trilogy replaces it; otherwise a second run silently doubles every chunk and
      // quietly corrupts any comparison of what each tier retrieves.
      appJdbcTemplate.update("DELETE FROM vector_store WHERE metadata ->> 'trilogy' = ?", trilogy);

      int written = 0;
      int done = 0;
      for (BookUploadDTO book : books) {
        written += ingestBook(trilogy, ownerId, book);
        done++;
        job.setBooksDone(done);
        job.setChunksWritten(written);
        jobRepo.save(job);
      }

      job.setStatus(IngestionStatus.COMPLETED);
      jobRepo.save(job);
    } catch (Exception e) {
      jobRepo.findById(jobId).ifPresent(job -> {
        job.setStatus(IngestionStatus.FAILED);
        job.setError(e.getMessage());
        jobRepo.save(job);
      });
    } finally {
      ClearanceContext.clear();
    }
  }

  private int ingestBook(String trilogy, String ownerId, BookUploadDTO book) {
    String text;
    try (PDDocument pdf = Loader.loadPDF(book.content())) {
      text = new PDFTextStripper().getText(pdf);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read \"" + book.filename() + "\" as a PDF", e);
    }

    Document bookDoc = Document.builder()
        .text(text)
        .metadata("trilogy", trilogy)
        // a string, so the RLS predicate metadata ->> 'book' compares like with like
        .metadata("book", String.valueOf(book.position()))
        .metadata("title", book.filename())
        .metadata("uploadedAt", System.currentTimeMillis())
        // retained for monitoring only — it is not an access-control input any more
        .metadata("owner", ownerId)
        .build();

    TextSplitter splitter = TokenTextSplitter.builder()
        .withChunkSize(500)
        .withMinChunkSizeChars(50)
        .build();

    List<Document> chunks = splitter.split(bookDoc);
    if (chunks.isEmpty()) {
      throw new IllegalStateException("No extractable text found in \"" + book.filename() + "\"");
    }
    vectorStore.add(chunks);
    return chunks.size();
  }
}
