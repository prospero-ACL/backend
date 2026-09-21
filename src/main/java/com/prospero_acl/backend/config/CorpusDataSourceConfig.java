package com.prospero_acl.backend.config;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import com.prospero_acl.backend.model.enums.SecurityLevel;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Splits database access in two.
 *
 * <p>The application DataSource keeps the existing role and serves JPA and Flyway; those tables are
 * not tier-gated. The corpus DataSource routes to one Postgres login role per clearance, so the
 * row-level security policies in V2 decide what the document store returns. Routing everything
 * through one DataSource would break JPA, because the tier roles have no rights on the entity tables.
 */
@Configuration
public class CorpusDataSourceConfig {

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource")
  public DataSourceProperties appDataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  public DataSource appDataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean
  public DataSource corpusDataSource(
      DataSourceProperties properties,
      @Value("${app.corpus.plebian-password}") String plebianPassword,
      @Value("${app.corpus.eques-password}") String equesPassword,
      @Value("${app.corpus.patrician-password}") String patricianPassword,
      @Value("${app.corpus.pool-size}") int poolSize) {

    Map<Object, Object> pools = new HashMap<>();
    pools.put(SecurityLevel.PLEBIAN, pool(properties, SecurityLevel.PLEBIAN, plebianPassword, poolSize));
    pools.put(SecurityLevel.EQUES, pool(properties, SecurityLevel.EQUES, equesPassword, poolSize));
    pools.put(SecurityLevel.PATRICIAN, pool(properties, SecurityLevel.PATRICIAN, patricianPassword, poolSize));

    AbstractRoutingDataSource router = new AbstractRoutingDataSource() {
      @Override
      protected Object determineCurrentLookupKey() {
        SecurityLevel level = ClearanceContext.get();
        if (level == null) {
          throw new IllegalStateException(
              "No clearance in context — refusing to open a corpus connection");
        }
        return level;
      }
    };
    router.setTargetDataSources(pools);
    return router;
  }

  @Bean
  public JdbcTemplate corpusJdbcTemplate(@Qualifier("corpusDataSource") DataSource corpusDataSource) {
    return new JdbcTemplate(corpusDataSource);
  }

  // Role names are fixed by V2__acl_roles_and_rls.sql; deriving them keeps the two in step.
  private DataSource pool(
      DataSourceProperties properties, SecurityLevel level, String password, int poolSize) {
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl(properties.getUrl());
    pool.setUsername("postgres_" + level.name().toLowerCase());
    pool.setPassword(password);
    pool.setMaximumPoolSize(poolSize);
    pool.setPoolName("corpus-" + level.name().toLowerCase());
    return pool;
  }
}
