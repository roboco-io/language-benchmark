package io.realworld

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path

@SpringBootApplication
class Application {
    private val log = LoggerFactory.getLogger(Application::class.java)

    @Bean
    fun startupReport(jdbc: JdbcTemplate) = ApplicationRunner {
        val journalMode = jdbc.queryForObject("PRAGMA journal_mode", String::class.java)
        val busyTimeout = jdbc.queryForObject("PRAGMA busy_timeout", Int::class.java)
        log.info(
            "Workers: {} Tomcat threads (CPU cores); SQLite journal_mode={}, busy_timeout={} ms",
            CPU_CORES, journalMode, busyTimeout,
        )
    }

    companion object {
        val CPU_CORES: Int = Runtime.getRuntime().availableProcessors()
    }
}

fun main(args: Array<String>) {
    // SQLite does not create missing parent directories for the database file.
    val dbPath = System.getenv("DB_PATH") ?: "./data/realworld.db"
    Path.of(dbPath).toAbsolutePath().parent?.let { Files.createDirectories(it) }

    val cores = Application.CPU_CORES
    SpringApplication(Application::class.java).apply {
        // Size the Tomcat worker pool (and the DB pool feeding it) to the number of CPU cores.
        setDefaultProperties(
            mapOf(
                "server.tomcat.threads.max" to cores,
                "server.tomcat.threads.min-spare" to cores,
                "spring.datasource.hikari.maximum-pool-size" to cores,
                "spring.datasource.hikari.minimum-idle" to 1,
            ),
        )
    }.run(*args)
}
