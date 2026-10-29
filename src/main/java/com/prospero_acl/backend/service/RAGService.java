package com.prospero_acl.backend.service;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class RAGService {

  private final ChatClient chatClient;
  private final VectorStore vectorStore;

  public RAGService(ChatClient.Builder builder, VectorStore vectorStore) {
    this.chatClient = builder.build();
    this.vectorStore = vectorStore;
  }

  // No ACL filter is passed: the vector store is reached through a connection whose Postgres role
  // already limits retrieval to the caller's clearance (see CorpusDataSourceConfig).
  public String query(String text, List<Message> history) {
    return chatClient
        .prompt()
        .messages(history)
        .user(text)
        .advisors(a -> a.advisors(QuestionAnswerAdvisor.builder(vectorStore).build()))
        .call()
        .content();
  }
}
