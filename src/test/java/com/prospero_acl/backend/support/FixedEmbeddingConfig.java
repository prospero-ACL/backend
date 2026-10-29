package com.prospero_acl.backend.support;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces OpenAI embeddings with one constant vector, so the access-control tests run offline and
 * every chunk is equally similar to every query. Whatever a similarity search returns is then
 * decided by row visibility alone — which is the thing under test.
 */
@TestConfiguration
public class FixedEmbeddingConfig {

  public static final int DIMENSIONS = 1536;

  @Bean
  @Primary
  EmbeddingModel fixedEmbeddingModel() {
    return new FixedEmbeddingModel();
  }

  /** The vector as a pgvector literal, for seeding rows directly in SQL. */
  public static String vectorLiteral() {
    return Arrays.toString(vector()).replace(" ", "");
  }

  private static float[] vector() {
    float[] v = new float[DIMENSIONS];
    v[0] = 1f;
    return v;
  }

  static class FixedEmbeddingModel implements EmbeddingModel {
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<Embedding> embeddings = IntStream.range(0, request.getInstructions().size())
          .mapToObj(i -> new Embedding(vector(), i))
          .toList();
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      return vector();
    }

    @Override
    public int dimensions() {
      return DIMENSIONS;
    }
  }
}
