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
        // "Today" (study streaks, daily limits, flashcards due) follows the learners' clock, not the server's: a host
        // running in UTC would otherwise start a new day at 7 a.m. Vietnam time. The JDBC driver gives every database
        // session this same zone, so CURRENT_DATE in SQL agrees with LocalDate.now() in Java.
        String zone = System.getenv().getOrDefault("APP_TIMEZONE", "Asia/Ho_Chi_Minh");
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(java.time.ZoneId.of(zone)));
        SpringApplication.run(YibiBackendApplication.class, args);
    }
}
