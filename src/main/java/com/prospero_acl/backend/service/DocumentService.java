package com.prospero_acl.backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.prospero_acl.backend.exception.EmptyDocumentException;
import com.prospero_acl.backend.exception.UnreadablePdfException;
import com.prospero_acl.backend.model.dto.ResponseDocumentDTO;
import com.prospero_acl.backend.model.enums.DocumentScope;

import java.io.IOException;
import java.util.Date;
import java.util.List;
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

  public List<ResponseDocumentDTO> getDocumentsByUser(String userId) {
    SearchRequest request = SearchRequest.builder()
        .query(" ")
        .topK(1000)
        .filterExpression("owner == '" + userId + "'")
        .build();

    List<ResponseDocumentDTO> searchResult = vectorStore.similaritySearch(request)
        .stream()
        .collect(Collectors.toMap(
            doc -> (String) doc.getMetadata().get("filename"),
            doc -> doc,
            (existing, replacement) -> existing // keep first chunk per filename
        ))
        .values()
        .stream()
        .map(doc -> new ResponseDocumentDTO(
            doc.getId(),
            (String) doc.getMetadata().get("filename"),
            new Date((Long) doc.getMetadata().get("uploadedAt")).toString()))
        .toList();

    return searchResult;
  }
}
