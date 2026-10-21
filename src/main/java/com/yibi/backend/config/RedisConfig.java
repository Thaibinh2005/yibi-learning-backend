package com.yibi.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis Configuration.
 *
 * Usage in Yibi:
 * 1. JWT Blacklist: keys like "blacklist:{token_hash}" with TTL = token remaining time
 * 2. Refresh Token Store: keys like "refresh:{uuid}" with value = userId, TTL = 7 days
 * 3. Stats Cache (Week 11-12): @Cacheable on admin stats queries
 *
 * Connection settings are in application.yml → spring.data.redis.*
 */
@Configuration
public class RedisConfig {

    /**
     * Configure RedisTemplate to use String serialization for both keys and values.
     * This avoids the default Java serialization issues.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.afterPropertiesSet();

        return template;
    }
}
