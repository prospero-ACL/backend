package com.prospero_acl.backend.repo;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorDatabase {

  @Bean
  public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
    return PgVectorStore
        .builder(jdbcTemplate, embeddingModel)
        .dimensions(1536)// better to specify dimensions
        .distanceType(PgDistanceType.COSINE_DISTANCE)// this is optional
        .indexType(PgVectorStore.PgIndexType.HNSW)// this is optional

        .build();
  }
}
