package com.auditsphere.remediation;

import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RemediationApplication {
  public static void main(String[] args) {
    if ("true".equals(System.getenv("MIGRATION_ONLY"))) {
      Flyway.configure()
          .dataSource(
              System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"))
          .load()
          .migrate();
      return;
    }
    SpringApplication.run(RemediationApplication.class, args);
  }

  /** Replaced in tests to evaluate due dates on a fixed day. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
