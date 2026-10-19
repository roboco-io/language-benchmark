package com.realworld;

import java.io.File;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        int cores = Runtime.getRuntime().availableProcessors();
        String dbPath = System.getenv().getOrDefault("DB_PATH", "./data/realworld.db");
        File parent = new File(dbPath).getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        SpringApplication app = new SpringApplication(Application.class);
        app.setDefaultProperties(Map.of(
                "server.tomcat.threads.max", String.valueOf(cores),
                "server.tomcat.threads.min-spare", String.valueOf(cores),
                "spring.datasource.hikari.maximum-pool-size", String.valueOf(cores)));
        app.run(args);
    }
}
