package io.realworld

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

@SpringBootApplication
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

@Configuration
class ServerConfig {
    private val log = LoggerFactory.getLogger(ServerConfig::class.java)
    private val cores = Runtime.getRuntime().availableProcessors()

    /** Size the embedded Tomcat worker pool to the number of available CPU cores. */
    @Bean
    fun tomcatThreads(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addConnectorCustomizers({ connector ->
                val handler = connector.protocolHandler
                if (handler is org.apache.coyote.AbstractProtocol<*>) {
                    handler.maxThreads = cores
                    handler.minSpareThreads = cores
                }
            })
            log.info("Tomcat worker threads sized to {} (available CPU cores)", cores)
        }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    companion object {
        /** Match the JDBC pool to the worker count so every worker can hold a connection. */
        @Bean
        @JvmStatic
        fun hikariSizer(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
                if (bean is HikariDataSource) {
                    val cores = Runtime.getRuntime().availableProcessors()
                    bean.maximumPoolSize = cores
                    bean.minimumIdle = 1
                }
                return bean
            }
        }
    }

    @Bean
    fun logSqliteSettings(jdbc: JdbcTemplate) = ApplicationRunner {
        val mode = jdbc.queryForObject("PRAGMA journal_mode", String::class.java)
        val timeout = jdbc.queryForObject("PRAGMA busy_timeout", Int::class.java)
        log.info("SQLite journal_mode={}, busy_timeout={} ms", mode, timeout)
    }
}
