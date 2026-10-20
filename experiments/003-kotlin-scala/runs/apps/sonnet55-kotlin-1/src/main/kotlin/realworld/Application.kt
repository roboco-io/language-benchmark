package realworld

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application

fun main(args: Array<String>) {
    // Size the Tomcat worker pool and the DB pool to the number of available CPU cores.
    val cores = Runtime.getRuntime().availableProcessors().toString()
    runApplication<Application>(*args) {
        setDefaultProperties(
            mapOf(
                "server.tomcat.threads.max" to cores,
                "server.tomcat.threads.min-spare" to cores,
                "spring.datasource.hikari.maximum-pool-size" to cores,
            )
        )
    }
}
