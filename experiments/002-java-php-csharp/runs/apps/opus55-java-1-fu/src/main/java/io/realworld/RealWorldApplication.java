package io.realworld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RealWorldApplication {

    public static void main(String[] args) throws IOException {
        // SQLite does not create missing parent directories for the database file.
        String dbPath = System.getenv().getOrDefault("DB_PATH", "data/realworld.db");
        Path parent = Path.of(dbPath).toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Size the Tomcat worker pool (and the JDBC pool) to the number of available CPU cores.
        int cores = Runtime.getRuntime().availableProcessors();
        SpringApplication app = new SpringApplication(RealWorldApplication.class);
        app.setDefaultProperties(Map.of(
                "server.tomcat.threads.max", String.valueOf(cores),
                "server.tomcat.threads.min-spare", String.valueOf(cores),
                "spring.datasource.hikari.maximum-pool-size", String.valueOf(cores),
                "spring.datasource.hikari.minimum-idle", String.valueOf(cores)));
        app.run(args);
    }
}
