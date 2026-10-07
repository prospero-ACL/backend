package com.prospero_acl.backend.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.document.Document;
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
 * Ingestion is patrician-only in the controller, but the database must refuse it on its own: these
 * calls bypass the controller entirely and still have to fail, on privileges.
 */
@Import(FixedEmbeddingConfig.class)
class CorpusWritePrivilegeTest extends PostgresIntegrationTest {

  @Autowired
  private VectorStore vectorStore;
  @Autowired
  @Qualifier("corpusJdbcTemplate")
  private JdbcTemplate corpusJdbcTemplate;
  @Autowired
  @Qualifier("appJdbcTemplate")
  private JdbcTemplate appJdbcTemplate;

  @BeforeEach
  void emptyCorpus() {
    appJdbcTemplate.update("TRUNCATE vector_store");
  }

  @AfterEach
  void clearClearance() {
    ClearanceContext.clear();
  }

  @ParameterizedTest
  @EnumSource(value = SecurityLevel.class, names = { "PLEBIAN", "EQUES" })
  void nonPatricianCannotInsertChunks(SecurityLevel level) {
    ClearanceContext.set(level);

    assertThatThrownBy(() -> vectorStore.add(List.of(chunk())))
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThat(chunksInCorpus()).isZero();
  }

  @Test
  void patricianCanInsertChunks() {
    // Control: without it, the negative cases above could pass because inserts are broken outright.
    ClearanceContext.set(SecurityLevel.PATRICIAN);

    vectorStore.add(List.of(chunk()));

    assertThat(chunksInCorpus()).isOne();
  }

  @ParameterizedTest
  @EnumSource(SecurityLevel.class)
  void tierRolesCannotReadApplicationTables(SecurityLevel level) {
    ClearanceContext.set(level);

    assertThatThrownBy(() -> corpusJdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class))
        .rootCause()
        .hasMessageContaining("permission denied");
  }

  @Test
  void patricianCannotRelabelAChunkIntoALowerBook() {
    // Patricians hold UPDATE, but no UPDATE policy exists, so RLS matches no rows: a chunk cannot be
    // moved from book 3 to book 1 to expose it to plebians.
    appJdbcTemplate.update(
        "INSERT INTO vector_store (content, metadata, embedding) VALUES ('x', ?::jsonb, ?::vector)",
        "{\"book\":\"3\"}", FixedEmbeddingConfig.vectorLiteral());
    ClearanceContext.set(SecurityLevel.PATRICIAN);

    int updated = corpusJdbcTemplate.update(
        "UPDATE vector_store SET metadata = jsonb_set(metadata, '{book}', '\"1\"')");

    assertThat(updated).isZero();
    assertThat(appJdbcTemplate.queryForObject(
        "SELECT metadata ->> 'book' FROM vector_store", String.class)).isEqualTo("3");
  }

  private Document chunk() {
    return Document.builder().text("forged chunk").metadata("book", "1").build();
  }

  private int chunksInCorpus() {
    return appJdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Integer.class);
  }
}
