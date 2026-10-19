package com.example.realworld;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RealWorldApplication {
    public static void main(String[] args) {
        SpringApplication.run(RealWorldApplication.class, args);
    }

    /** Sizes the Tomcat worker thread pool to the number of available CPU cores. */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return factory -> factory.addConnectorCustomizers(connector -> {
            var handler = connector.getProtocolHandler();
            if (handler instanceof org.apache.coyote.AbstractProtocol<?> protocol) {
                protocol.setMaxThreads(cores);
                protocol.setMinSpareThreads(cores);
            }
        });
    }
}
