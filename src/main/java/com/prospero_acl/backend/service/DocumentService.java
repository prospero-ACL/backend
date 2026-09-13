package com.prospero_acl.backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.prospero_acl.backend.exception.EmptyDocumentException;
import com.prospero_acl.backend.exception.UnreadablePdfException;
import com.prospero_acl.backend.model.User;
import com.prospero_acl.backend.model.dto.ResponseDocumentDTO;
import com.prospero_acl.backend.model.enums.DocumentScope;
import com.prospero_acl.backend.model.enums.SecurityLevel;
import com.prospero_acl.backend.repo.UserRepo;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

@Service
public class DocumentService {

  @Autowired
  private VectorStore vectorStore;

  @Autowired
  private UserRepo userRepo;

  public void saveDocument(MultipartFile file, String userId, DocumentScope scope) {
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
        .metadata("privacy", scope.name().toLowerCase())
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

  // Uses the same visibility rule as RAG retrieval (ReportService), so this
  // listing/selection endpoint never offers a document the caller couldn't
  // actually have retrieved into a report. For EQUES/PATRICIAN this can
  // include documents owned by other users, not just the caller's own.
  public List<ResponseDocumentDTO> getDocumentsByUser(String callerId, SecurityLevel level) {
    String filterExpression = AclFilter.visibilityClause(level, UUID.fromString(callerId));

    SearchRequest request = SearchRequest.builder()
        .query(" ")
        .topK(1000)
        .filterExpression(filterExpression)
        .build();

    List<Document> uniqueDocs = vectorStore.similaritySearch(request)
        .stream()
        .collect(Collectors.toMap(
            // filename alone isn't unique once results can span owners, so
            // dedupe chunks by (filename, owner) instead.
            doc -> doc.getMetadata().get("filename") + "|" + doc.getMetadata().get("owner"),
            doc -> doc,
            (existing, replacement) -> existing // keep first chunk per document
        ))
        .values()
        .stream()
        .toList();

    Map<UUID, String> ownerNames = resolveOwnerNames(uniqueDocs);

    return uniqueDocs.stream()
        .map(doc -> {
          UUID ownerId = UUID.fromString(doc.getMetadata().get("owner").toString());
          String ownerName = ownerNames.getOrDefault(ownerId, ownerId.toString());
          return new ResponseDocumentDTO(
              doc.getId(),
              (String) doc.getMetadata().get("filename"),
              new Date((Long) doc.getMetadata().get("uploadedAt")).toString(),
              doc.getMetadata().get("privacy").toString(),
              ownerName);
        })
        .toList();
  }

  private Map<UUID, String> resolveOwnerNames(List<Document> docs) {
    Set<UUID> ownerIds = docs.stream()
        .map(doc -> UUID.fromString(doc.getMetadata().get("owner").toString()))
        .collect(Collectors.toSet());

    return userRepo.findAllById(ownerIds).stream()
        .filter(user -> user.getName() != null)
        .collect(Collectors.toMap(User::getId, User::getName));
  }
}
