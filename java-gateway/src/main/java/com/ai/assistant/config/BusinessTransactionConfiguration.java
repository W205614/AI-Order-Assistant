package com.ai.assistant.config;

import com.ai.assistant.security.UserContext;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Internal AI deadlines apply at transaction admission and immediately before commit. */
@Configuration
public class BusinessTransactionConfiguration {
  @Bean
  public JdbcTransactionManager transactionManager(DataSource dataSource) {
    return new JdbcTransactionManager(dataSource) {
      @Override
      protected void doBegin(Object transaction, TransactionDefinition definition) {
        UserContext.checkDeadline();
        super.doBegin(transaction, definition);
      }

      @Override
      protected void prepareForCommit(DefaultTransactionStatus status) {
        UserContext.checkDeadline();
        super.prepareForCommit(status);
      }
    };
  }
}
