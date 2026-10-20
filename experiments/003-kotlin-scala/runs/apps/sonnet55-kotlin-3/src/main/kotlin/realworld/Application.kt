package realworld

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths
import javax.sql.DataSource

@SpringBootApplication
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

@Configuration
class InfraConfig {
    private val cores = Runtime.getRuntime().availableProcessors()

    /** SQLite in WAL mode with a 5000 ms busy timeout; write transactions take the write lock up front. */
    @Bean
    fun dataSource(@Value("\${app.db.path}") path: String): DataSource {
        val file = Paths.get(path).toAbsolutePath()
        file.parent?.let { Files.createDirectories(it) }
        val cfg = HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:$file?journal_mode=WAL&busy_timeout=5000&foreign_keys=true" +
                "&synchronous=NORMAL&transaction_mode=IMMEDIATE"
            maximumPoolSize = maxOf(cores, 4)
            minimumIdle = 1
        }
        return HikariDataSource(cfg)
    }

    /** Tomcat worker thread pool sized to the number of available CPU cores. */
    @Bean
    fun tomcatCustomizer() = WebServerFactoryCustomizer<TomcatServletWebServerFactory> { factory ->
        factory.addConnectorCustomizers({ connector ->
            val handler = connector.protocolHandler
            if (handler is org.apache.coyote.AbstractProtocol<*>) {
                handler.maxThreads = cores
                handler.minSpareThreads = cores
            }
        })
    }
}
