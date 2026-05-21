package com.jvmobservability.demo.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configures MongoDB driver timeouts so MongoHealthIndicator reports DOWN
 * quickly when MongoDB stops, instead of hanging for 30 seconds (driver default).
 *
 * Uses pure MongoDB driver API (com.mongodb.*) — no Spring Boot autoconfigure import.
 * Spring Boot 4.x uses this MongoClientSettings bean directly when present.
 *
 * Without this:
 *   serverSelectionTimeout = 30s → health check hangs 30s before showing DOWN
 *   heartbeatFrequencyMS   = 10s → driver blind for 10s after MongoDB stops
 *
 * With this:
 *   serverSelectionTimeout = 3s  → health shows DOWN within 3s
 *   heartbeatFrequency     = 2s  → driver detects MongoDB down within 2s
 *   connectTimeout         = 3s  → new connection attempt fails fast
 */
@Configuration
public class MongoTimeoutConfig {

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;

    @Bean
    public MongoClientSettings mongoClientSettings() {
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongoUri))
                .applyToClusterSettings(cluster ->
                        cluster.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(socket ->
                        socket.connectTimeout(3, TimeUnit.SECONDS))
                .applyToServerSettings(server ->
                        server.heartbeatFrequency(2, TimeUnit.SECONDS)
                              .minHeartbeatFrequency(500, TimeUnit.MILLISECONDS))
                .build();
    }
}
