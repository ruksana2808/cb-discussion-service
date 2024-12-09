package com.igot.cb.pores.config;

import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class SearchResultRedisConfig {
    @Bean
    public RedisTemplate<String, SearchResult> redisTemplateForSearchResult(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, SearchResult> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        return redisTemplate;
    }
}
