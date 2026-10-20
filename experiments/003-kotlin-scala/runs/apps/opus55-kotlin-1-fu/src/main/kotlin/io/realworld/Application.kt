package io.realworld

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.apache.coyote.AbstractProtocol

@SpringBootApplication
class Application {
    private val log = LoggerFactory.getLogger(Application::class.java)

    /** Sizes the embedded Tomcat worker pool to the number of available CPU cores. */
    @Bean
    fun tomcatThreads(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            val cores = Runtime.getRuntime().availableProcessors()
            factory.addConnectorCustomizers({ connector ->
                val handler = connector.protocolHandler
                if (handler is AbstractProtocol<*>) {
                    handler.maxThreads = cores
                    handler.minSpareThreads = cores
                }
            })
            log.info("Tomcat worker threads sized to {} CPU cores", cores)
        }

    /** Logs the effective SQLite settings so the WAL / busy_timeout configuration is visible at startup. */
    @Bean
    fun sqliteSettingsReporter(jdbc: JdbcTemplate) = ApplicationRunner {
        val journalMode = jdbc.queryForObject("PRAGMA journal_mode", String::class.java)
        val busyTimeout = jdbc.queryForObject("PRAGMA busy_timeout", Int::class.java)
        log.info("SQLite journal_mode={} busy_timeout={}ms", journalMode, busyTimeout)
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
