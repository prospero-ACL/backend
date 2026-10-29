package com.prospero_acl.backend.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.prospero_acl.backend.config.ClearanceContext;
import com.prospero_acl.backend.model.enums.SecurityLevel;
import com.prospero_acl.backend.support.FixedEmbeddingConfig;
import com.prospero_acl.backend.support.PostgresIntegrationTest;

/**
 * The thesis claim at its lowest level: a connection routed for a tier cannot read chunks above
 * that tier, no matter how the query is phrased. Everything goes through the application's own
 * corpus path (ClearanceContext → routing DataSource → tier pool → RLS), not a hand-made
 * connection, so a wiring mistake in the app fails here too.
 */
@Import(FixedEmbeddingConfig.class)
class CorpusReadIsolationTest extends PostgresIntegrationTest {

  private static final String UNLABELLED = "unlabelled";

  @Autowired
  private VectorStore vectorStore;
  @Autowired
  @Qualifier("corpusJdbcTemplate")
  private JdbcTemplate corpusJdbcTemplate;
  @Autowired
  @Qualifier("appJdbcTemplate")
  private JdbcTemplate appJdbcTemplate;

  @BeforeEach
  void seedOneChunkPerBook() {
    // Seeded through the superuser connection, so the fixture does not depend on what is under test.
    appJdbcTemplate.update("TRUNCATE vector_store");
    for (String book : List.of("1", "2", "3")) {
      seed("book " + book + " text", "{\"trilogy\":\"Test Trilogy\",\"book\":\"" + book + "\"}");
    }
    // A chunk from before the trilogy model: no book label at all.
    seed("unlabelled text", "{\"trilogy\":\"Test Trilogy\"}");
  }

  @AfterEach
  void clearClearance() {
    ClearanceContext.clear();
  }

  @Test
  void plebianRetrievesOnlyBookOne() {
    assertThat(retrievableBooks(SecurityLevel.PLEBIAN)).containsExactlyInAnyOrder("1");
  }

  @Test
  void equesRetrievesBooksOneAndTwo() {
    assertThat(retrievableBooks(SecurityLevel.EQUES)).containsExactlyInAnyOrder("1", "2");
  }

  @Test
  void patricianRetrievesEverything() {
    assertThat(retrievableBooks(SecurityLevel.PATRICIAN))
        .containsExactlyInAnyOrder("1", "2", "3", UNLABELLED);
  }

  @Test
  void plebianCannotReadHigherBooksEvenWhenAskingForThemExplicitly() {
    ClearanceContext.set(SecurityLevel.PLEBIAN);
    Integer visible = corpusJdbcTemplate.queryForObject(
        "SELECT count(*) FROM vector_store WHERE metadata ->> 'book' IN ('2', '3')", Integer.class);
    assertThat(visible).isZero();
  }

  @Test
  void equesCannotReadBookThreeEvenWhenAskingForItExplicitly() {
    ClearanceContext.set(SecurityLevel.EQUES);
    Integer visible = corpusJdbcTemplate.queryForObject(
        "SELECT count(*) FROM vector_store WHERE metadata ->> 'book' = '3'", Integer.class);
    assertThat(visible).isZero();
  }

  @Test
  void eachTierRunsAsItsOwnNonSuperuserRole() {
    // Catches the app role being reused for the corpus: it is a superuser and would bypass RLS.
    for (SecurityLevel level : SecurityLevel.values()) {
      ClearanceContext.set(level);
      String role = corpusJdbcTemplate.queryForObject("SELECT current_user", String.class);
      Boolean bypassesRls = corpusJdbcTemplate.queryForObject(
          "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);

      assertThat(role).isEqualTo("postgres_" + level.name().toLowerCase());
      assertThat(bypassesRls).isFalse();
    }
  }

  @Test
  void corpusConnectionIsRefusedWithoutAClearance() {
    // Fails loudly rather than defaulting to some tier.
    assertThatThrownBy(() -> corpusJdbcTemplate.queryForObject("SELECT 1", Integer.class))
        .rootCause()
        .hasMessageContaining("No clearance in context");
  }

  private Set<String> retrievableBooks(SecurityLevel level) {
    ClearanceContext.set(level);
    List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
        .query("anything")
        .topK(100)
        .similarityThresholdAll()
        .build());
    return results.stream()
        .map(doc -> Objects.toString(doc.getMetadata().get("book"), UNLABELLED))
        .collect(Collectors.toSet());
  }

  private void seed(String content, String metadataJson) {
    appJdbcTemplate.update(
        "INSERT INTO vector_store (content, metadata, embedding) VALUES (?, ?::jsonb, ?::vector)",
        content, metadataJson, FixedEmbeddingConfig.vectorLiteral());
  }
}
