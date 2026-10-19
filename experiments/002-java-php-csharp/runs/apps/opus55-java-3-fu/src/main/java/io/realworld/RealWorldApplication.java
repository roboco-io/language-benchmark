package io.realworld;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RealWorldApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RealWorldApplication.class);
        // Size the request thread pool (and the matching connection pool) to the
        // number of available CPU cores. These are defaults only, so explicit
        // configuration (env vars, properties) can still override them.
        int cores = Runtime.getRuntime().availableProcessors();
        app.setDefaultProperties(Map.of(
                "server.tomcat.threads.max", String.valueOf(cores),
                "server.tomcat.threads.min-spare", String.valueOf(cores),
                "spring.datasource.hikari.maximum-pool-size", String.valueOf(cores),
                "spring.datasource.hikari.minimum-idle", String.valueOf(Math.min(cores, 2))));
        app.run(args);
    }
}
