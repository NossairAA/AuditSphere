package com.auditsphere.notification;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NotificationApplication {
  public static void main(String[] args) {
    if ("true".equals(System.getenv("MIGRATION_ONLY"))) {
      Flyway.configure()
          .dataSource(
              System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"))
          .load()
          .migrate();
      return;
    }
    SpringApplication.run(NotificationApplication.class, args);
  }
}
