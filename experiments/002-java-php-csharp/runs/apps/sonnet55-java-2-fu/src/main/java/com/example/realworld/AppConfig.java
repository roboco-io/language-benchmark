package com.example.realworld;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    static final int CORES = Runtime.getRuntime().availableProcessors();

    /** SQLite in WAL mode with a 5000 ms busy timeout, applied to every pooled connection via URL parameters. */
    @Bean
    DataSource dataSource(@Value("${app.db-path}") String path) {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + path
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&foreign_keys=true");
        cfg.setMaximumPoolSize(Math.max(4, CORES));
        cfg.setMinimumIdle(1);
        return new HikariDataSource(cfg);
    }

    /** Tomcat worker thread pool sized to the number of available CPU cores. */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatThreads() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            var handler = connector.getProtocolHandler();
            if (handler instanceof org.apache.coyote.AbstractProtocol<?> p) {
                p.setMaxThreads(CORES);
                p.setMinSpareThreads(CORES);
            }
        });
    }
}
