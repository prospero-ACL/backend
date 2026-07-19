package com.prospero_acl.backend.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

import com.prospero_acl.backend.model.LlmReply;

@Service
public class RAGService {

  private ChatClient chatClient;
  private ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

  public String query(String text, Filter.Expression filter) {

    String chatResponse = chatClient
        .prompt(text)
        // in the advisor we embed the knoledge source ie the vector store
        .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
        .call()
        .content();
  }
}
