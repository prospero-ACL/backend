package com.prospero_acl.backend.repo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

@Service
public class DocumentService {

  @Autowired
  private VectorStore vectorStore;

  public void saveDocument(String text) {
    Document textDoc = Document.builder()
        .text(text)
        .metadata("source", "user-input")
        .build();

    TextSplitter splitter = TokenTextSplitter.builder()
        .withChunkSize(500)
        .withMinChunkSizeChars(50)
        .build();

    List<Document> documents = splitter.split(textDoc);

    vectorStore.add(documents);
  }

}
