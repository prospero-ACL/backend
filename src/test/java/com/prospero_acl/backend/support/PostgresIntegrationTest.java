package com.prospero_acl.backend.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the whole application against a throwaway pgvector Postgres that Flyway builds from V1, so
 * the tier roles and RLS policies under test are exactly the ones the migrations create.
 *
 * The container is started once per JVM and shared by every test class. Each class is responsible
 * for clearing the tables it seeds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

  // The container's own user is a superuser, standing in for the app role exactly as in production:
  // it bypasses RLS, which is why chunk reads must never go through it.
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);

    // Fed to V2 as Flyway placeholders and to the per-tier pools, so the two always agree.
    for (String tier : new String[] { "plebian", "eques", "patrician" }) {
      String password = "test-" + tier + "-password";
      registry.add("spring.flyway.placeholders[" + tier + "_password]", () -> password);
      registry.add("app.corpus." + tier + "-password", () -> password);
    }

    registry.add("jwt.secret", () -> "integration-test-secret-at-least-256-bits-long-0123456789");
    registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test");
    registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test");
    registry.add("spring.security.oauth2.client.registration.github.client-id", () -> "test");
    registry.add("spring.security.oauth2.client.registration.github.client-secret", () -> "test");

    // Only tests tagged "llm" make OpenAI calls; everything else runs offline with a placeholder.
    String openAiKey = System.getenv("OPENAI_API_KEY");
    registry.add("spring.ai.openai.api-key",
        () -> openAiKey == null || openAiKey.isBlank() ? "test-placeholder" : openAiKey);
  }
}
