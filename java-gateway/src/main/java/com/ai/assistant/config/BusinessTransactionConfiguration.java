package com.ai.assistant.config;

import com.ai.assistant.security.UserContext;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.DelegatingTransactionDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Internal AI deadlines apply at transaction admission and immediately before commit. */
@Configuration
public class BusinessTransactionConfiguration {
  public BusinessTransactionConfiguration(
      @Value("${spring.datasource.hikari.connection-timeout:3000}") long connectionTimeout,
      @Value("${spring.datasource.hikari.data-source-properties.connectTimeout:3000}") int connectTimeout,
      @Value("${spring.datasource.hikari.data-source-properties.socketTimeout:10000}") int socketTimeout) {
    org.springframework.util.Assert.isTrue(connectionTimeout > 0 && connectTimeout > 0 && socketTimeout > 0,
        "Database connection and socket timeouts must be positive");
  }

  @Bean
  public JdbcTransactionManager transactionManager(DataSource dataSource,
      @Value("${app.database.timeout-seconds:8}") int timeout) {
    org.springframework.util.Assert.isTrue(timeout > 0, "Database timeout must be positive");
    return new JdbcTransactionManager(dataSource) {
      @Override
      protected void doBegin(Object transaction, TransactionDefinition definition) {
        UserContext.checkDeadline();
        int configured = definition.getTimeout() == TransactionDefinition.TIMEOUT_DEFAULT
            ? timeout : Math.min(timeout, definition.getTimeout());
        int bounded = UserContext.databaseTimeoutSeconds(configured);
        super.doBegin(transaction, new DelegatingTransactionDefinition(definition) {
          @Override public int getTimeout() { return bounded; }
        });
      }

      @Override
      protected void prepareForCommit(DefaultTransactionStatus status) {
        UserContext.checkDeadline();
        super.prepareForCommit(status);
      }
    };
  }

  @Bean
  public JdbcTemplate jdbcTemplate(DataSource dataSource,
      @Value("${app.database.timeout-seconds:8}") int timeout) {
    org.springframework.util.Assert.isTrue(timeout > 0, "Database timeout must be positive");
    var jdbc = new JdbcTemplate(dataSource) {
      @Override protected void applyStatementSettings(java.sql.Statement statement)
          throws java.sql.SQLException {
        super.applyStatementSettings(statement);
        int bounded = UserContext.databaseTimeoutSeconds(timeout);
        int existing = statement.getQueryTimeout();
        statement.setQueryTimeout(existing > 0 ? Math.min(existing, bounded) : bounded);
      }
    };
    jdbc.setQueryTimeout(timeout);
    return jdbc;
  }
}
