package realworld

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application

fun main(args: Array<String>) {
    // Size the Tomcat thread pool and the DB pool to the available CPU cores unless overridden.
    val cores = Runtime.getRuntime().availableProcessors().toString()
    System.getProperty("server.tomcat.threads.max") ?: System.setProperty("server.tomcat.threads.max", cores)
    System.getProperty("server.tomcat.threads.min-spare") ?: System.setProperty("server.tomcat.threads.min-spare", cores)
    System.getProperty("spring.datasource.hikari.maximum-pool-size") ?: System.setProperty("spring.datasource.hikari.maximum-pool-size", cores)
    runApplication<Application>(*args)
}
