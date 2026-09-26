package com.example.platform.shared.cache;

import com.example.platform.catalog.application.ProductService.ProductChanged;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProductCacheInvalidator {
    private final CacheManager caches;
    public ProductCacheInvalidator(CacheManager caches) { this.caches = caches; }

    @TransactionalEventListener(fallbackExecution = true)
    public void afterCommit(ProductChanged event) {
        // A Redis failure after commit must not turn a successful write into an HTTP failure.
        for (String name : new String[]{"products", "product"}) {
            try {
                var cache = caches.getCache(name);
                if (cache != null) cache.clear();
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(ProductCacheInvalidator.class)
                        .warn("Could not invalidate {} after commit; TTL bounds stale entries", name);
            }
        }
    }
}
