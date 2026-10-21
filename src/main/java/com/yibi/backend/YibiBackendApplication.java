package com.yibi.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Yibi Backend — Main Application Entry Point
 *
 * Smart Chinese Learning Platform (HSK/HSKK)
 * Stack: Spring Boot 3.3.4 | Java 21 | PostgreSQL | Redis | MinIO
 */
@SpringBootApplication
@ConfigurationPropertiesScan  // Enables @ConfigurationProperties beans (e.g., JwtProperties, MinioProperties)
public class YibiBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(YibiBackendApplication.class, args);
    }
}
