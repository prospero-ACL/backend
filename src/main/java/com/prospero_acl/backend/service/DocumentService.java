package com.prospero_acl.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.dto.ResponseDocumentDto;

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

  public void saveDocument(String fileName, String text, String userId) {
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
    vectorStore.add(documents);
  }

  public List<ResponseDocumentDto> getDocumentsByUser(String userId) {
    SearchRequest request = SearchRequest.builder()
        .query(" ")
        .topK(1000)
        .filterExpression("owner == '" + userId + "'")
        .build();

    List<ResponseDocumentDto> searchResult = vectorStore.similaritySearch(request)
        .stream()
        .collect(Collectors.toMap(
            doc -> (String) doc.getMetadata().get("filename"),
            doc -> doc,
            (existing, replacement) -> existing // keep first chunk per filename
        ))
        .values()
        .stream()
        .map(doc -> new ResponseDocumentDto(
            (String) doc.getMetadata().get("filename"),
            new Date((Long) doc.getMetadata().get("uploadedAt")).toString()))
        .toList();

    return searchResult;
  }
}
