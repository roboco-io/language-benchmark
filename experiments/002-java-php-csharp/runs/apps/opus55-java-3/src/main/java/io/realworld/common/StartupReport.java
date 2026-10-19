package io.realworld.common;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Logs the effective concurrency and SQLite settings once the server is ready. */
@Component
public class StartupReport {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

    private final JdbcTemplate jdbc;
    private final int tomcatThreads;

    public StartupReport(DataSource dataSource, @Value("${server.tomcat.threads.max}") int tomcatThreads) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tomcatThreads = tomcatThreads;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        String journalMode = jdbc.queryForObject("PRAGMA journal_mode", String.class);
        Integer busyTimeout = jdbc.queryForObject("PRAGMA busy_timeout", Integer.class);
        log.info("cpu cores={}, tomcat threads={}, sqlite journal_mode={}, busy_timeout={}ms",
                Runtime.getRuntime().availableProcessors(), tomcatThreads, journalMode, busyTimeout);
    }
}
