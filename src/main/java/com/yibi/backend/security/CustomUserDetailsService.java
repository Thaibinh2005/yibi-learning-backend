package com.yibi.backend.security;

import com.yibi.backend.entity.User;
import com.yibi.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Custom UserDetailsService — bridges Yibi's User entity with Spring Security.
 *
 * loadUserByUsername accepts both username and email for flexible login.
 * Locked accounts (isActive = false) are reported as disabled.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(usernameOrEmail)
                .or(() -> userRepository.findByEmail(usernameOrEmail))
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with username or email: " + usernameOrEmail
                ));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                // Map ROLE_USER → GrantedAuthority("ROLE_USER")
                .authorities(List.of(new SimpleGrantedAuthority(user.getRole().name())))
                .accountExpired(false)
                .accountLocked(!user.isActive())       // Blocked users → 401
                .credentialsExpired(false)
                .disabled(!user.isActive())
                .build();
    }
}
