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
    if ("true".equals(System.getenv("WORKFLOW_WORKER_ONLY"))) {
      com.auditsphere.audit.workflow.ReviewWorker.run();
      return;
    }
    SpringApplication.run(AuditApplication.class, args);
  }
}
