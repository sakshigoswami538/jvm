package com.jvmobservability.demo.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * KafkaHealthIndicator — checks Kafka broker reachability via TCP socket.
 *
 * Spring Boot 4.x moved HealthIndicator to org.springframework.boot.health.contributor
 * (was org.springframework.boot.actuate.health in Spring Boot 3.x).
 *
 * Bean name "kafka" → appears as "kafka" component in:
 *   /actuator/health           → always shown
 *   /actuator/health/readiness → shown because kafka is in the readiness group
 *
 * Test:
 *   docker stop kafka  → readiness 503 DOWN  (kafka component DOWN)
 *   docker start kafka → readiness 200 UP    (kafka component UP)
 */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private static final int TIMEOUT_MS = 3000;

    @Value("${kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Override
    public Health health() {
        String[] parts = bootstrapServers.split(":");
        String host = parts[0];
        int    port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9092;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return Health.up()
                    .withDetail("bootstrap-servers", bootstrapServers)
                    .withDetail("reachable", true)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("bootstrap-servers", bootstrapServers)
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
