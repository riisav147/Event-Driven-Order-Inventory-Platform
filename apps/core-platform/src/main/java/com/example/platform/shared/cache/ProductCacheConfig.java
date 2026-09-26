package com.example.platform.shared.cache;

import com.example.platform.catalog.api.ProductDtos.ProductResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
public class ProductCacheConfig implements CachingConfigurer {
    private static final Logger log = LoggerFactory.getLogger(ProductCacheConfig.class);

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper mapper) {
        var base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(2)).disableCachingNullValues()
                .computePrefixWith(name -> "platform:v1:" + name + ":");
        var product = new Jackson2JsonRedisSerializer<ProductResponse>(mapper, ProductResponse.class);
        var listType = mapper.getTypeFactory().constructCollectionType(List.class, ProductResponse.class);
        var products = new Jackson2JsonRedisSerializer<List<ProductResponse>>(mapper, listType);
        return RedisCacheManager.builder(connectionFactory).cacheDefaults(base)
                .withInitialCacheConfigurations(Map.of(
                        "product", base.serializeValuesWith(SerializationPair.fromSerializer(product)),
                        "products", base.serializeValuesWith(SerializationPair.fromSerializer(products))))
                .enableStatistics().disableCreateOnMissingCache().build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            private void failed(Cache cache) {
                // Do not log keys or cached payloads. Reads fall back to PostgreSQL.
                log.warn("Cache operation failed for {}; cached data expires within two minutes", cache.getName());
            }
            public void handleCacheGetError(RuntimeException e, Cache c, Object key) { failed(c); }
            public void handleCachePutError(RuntimeException e, Cache c, Object key, Object value) { failed(c); }
            public void handleCacheEvictError(RuntimeException e, Cache c, Object key) { failed(c); }
            public void handleCacheClearError(RuntimeException e, Cache c) { failed(c); }
        };
    }
}
