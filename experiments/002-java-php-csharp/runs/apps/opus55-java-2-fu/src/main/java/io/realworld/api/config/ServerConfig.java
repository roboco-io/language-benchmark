package io.realworld.api.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.coyote.AbstractProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServerConfig {
    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

    /** Sizes the embedded Tomcat worker pool to the number of available CPU cores. */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatThreadPoolCustomizer() {
        int cores = Runtime.getRuntime().availableProcessors();
        return factory -> factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol) {
                protocol.setMaxThreads(cores);
                protocol.setMinSpareThreads(cores);
            }
            log.info("Tomcat worker threads sized to {} (available CPU cores)", cores);
        });
    }

    /** Logs the effective SQLite pragmas so the WAL / busy_timeout settings are verifiable. */
    @Bean
    public ApplicationRunner sqlitePragmaReporter(DataSource dataSource) {
        return args -> {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                String journalMode;
                int busyTimeout;
                try (ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
                    journalMode = rs.next() ? rs.getString(1) : "?";
                }
                try (ResultSet rs = s.executeQuery("PRAGMA busy_timeout")) {
                    busyTimeout = rs.next() ? rs.getInt(1) : -1;
                }
                log.info("SQLite journal_mode={} busy_timeout={}ms", journalMode, busyTimeout);
            }
        };
    }
}
