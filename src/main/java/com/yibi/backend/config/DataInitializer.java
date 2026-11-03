package com.yibi.backend.config;

import com.yibi.backend.entity.Role;
import com.yibi.backend.entity.User;
import com.yibi.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;


import org.springframework.core.annotation.Order;

@Slf4j
@Configuration
@Order(2)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        seedUsers();
        // HSK vocabulary and grammar are imported from versioned V6 sources.
        // Do not seed legacy vocabulary/course rows at application startup.
    }

    private void seedUsers() {
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = User.builder()
                    .username("admin")
                    .email("admin@yibi.com")
                    .passwordHash(passwordEncoder.encode("Admin@123456"))
                    .role(Role.ROLE_ADMIN)
                    .isActive(true)
                    .hskLevel(6)
                    .build();
            userRepository.save(admin);
            log.info("Default ADMIN user created: admin / Admin@123456");
        }

        if (userRepository.findByUsername("student").isEmpty()) {
            User student = User.builder()
                    .username("student")
                    .email("student@yibi.com")
                    .passwordHash(passwordEncoder.encode("Student@123456"))
                    .role(Role.ROLE_USER)
                    .isActive(true)
                    .hskLevel(1)
                    .build();
            userRepository.save(student);
            log.info("Default USER created: student / Student@123456");
        }
    }

}
