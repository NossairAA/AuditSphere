package com.auditsphere.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuditApplication {
  public static void main(String[] args) {
    if ("true".equals(System.getenv("MIGRATION_ONLY"))) {
      DatabaseMigration.run();
      return;
    }
    SpringApplication.run(AuditApplication.class, args);
  }
}
