package com.prospero_acl.backend.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

@Service
public class RAGService {

  private final ChatClient chatClient;
  private final VectorStore vectorStore;

  public RAGService(ChatClient.Builder builder, VectorStore vectorStore) {
    this.chatClient = builder.build();
    this.vectorStore = vectorStore;
  }

  public String query(String text, Filter.Expression filter) {
    return chatClient
        .prompt(text)
        .advisors(a -> a
            .param(QuestionAnswerAdvisor.FILTER_EXPRESSION, filter)
            .advisors(QuestionAnswerAdvisor.builder(vectorStore).build()))
        .call()
        .content();
  }
}
