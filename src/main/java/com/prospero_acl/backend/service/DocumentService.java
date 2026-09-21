package com.prospero_acl.backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.prospero_acl.backend.exception.EmptyDocumentException;
import com.prospero_acl.backend.exception.UnreadablePdfException;

import java.io.IOException;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

@Service
public class DocumentService {

  @Autowired
  private VectorStore vectorStore;

  // Deliberately the application connection, not the clearance-routed one: trilogy titles are
  // public to every tier, only the chunks behind them are gated.
  @Autowired
  @Qualifier("appJdbcTemplate")
  private JdbcTemplate appJdbcTemplate;

  public void saveDocument(MultipartFile file, String userId) {
    String fileName = file.getOriginalFilename();
    String text;
    try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
      text = new PDFTextStripper().getText(pdf);
    } catch (IOException e) {
      throw new UnreadablePdfException("Could not read \"" + fileName + "\" as a PDF", e);
    }

    Document textDoc = Document.builder()
        .text(text)
        .metadata("owner", userId)
        .metadata("filename", fileName)
        .metadata("uploadedAt", System.currentTimeMillis())
        .build();

    TextSplitter splitter = TokenTextSplitter.builder()
        .withChunkSize(500)
        .withMinChunkSizeChars(50)
        .build();

    List<Document> documents = splitter.split(textDoc);
    if (documents.isEmpty()) {
      throw new EmptyDocumentException("No extractable text found in \"" + fileName + "\"");
    }
    vectorStore.add(documents);
  }

  public List<String> listTrilogies() {
    return appJdbcTemplate.queryForList(
        "SELECT DISTINCT metadata ->> 'trilogy' FROM vector_store "
            + "WHERE metadata ->> 'trilogy' IS NOT NULL ORDER BY 1",
        String.class);
  }
}
