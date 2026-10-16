package com.auditsphere.audit;

import org.flywaydb.core.Flyway;

final class DatabaseMigration {
  static void run() {
    Flyway.configure()
        .dataSource(System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"))
        .load()
        .migrate();
  }
}
