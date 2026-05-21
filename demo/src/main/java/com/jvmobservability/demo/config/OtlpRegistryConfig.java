package com.jvmobservability.demo.config;

import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * Manually creates OtlpMeterRegistry.
 *
 * Spring Boot 4.x changed how OTLP metrics are auto-configured.
 * The management.otlp.metrics.export.* properties no longer trigger
 * auto-creation of OtlpMeterRegistry in all cases.
 *
 * This bean is picked up by Spring Boot's MeterRegistryPostProcessor
 * and automatically added to the composite MeterRegistry, so all
 * metrics (JVM, HTTP, custom) are pushed to the OTel Collector.
 *
 * Flow after this bean is created:
 *   Every 10s → OtlpMeterRegistry.publish()
 *     → serializes all meters as OTLP Protobuf
 *     → POST http://localhost:4318/v1/metrics
 *     → OTel Collector receives → Elasticsearch exporter → otel-metrics index
 */
@Configuration
public class OtlpRegistryConfig {

    private static final Logger log = LoggerFactory.getLogger(OtlpRegistryConfig.class);

    @Value("${management.otlp.metrics.export.url:http://localhost:4318/v1/metrics}")
    private String otlpUrl;

    @Value("${management.otlp.metrics.export.step:10s}")
    private Duration step;

    @Bean
    @ConditionalOnMissingBean(OtlpMeterRegistry.class)
    public OtlpMeterRegistry otlpMeterRegistry() {
        log.info("[OTel Pipeline] Creating OtlpMeterRegistry → pushing to {} every {}s",
                otlpUrl, step.getSeconds());

        OtlpConfig config = new OtlpConfig() {
            @Override
            public String url() {
                return otlpUrl;
            }

            @Override
            public Duration step() {
                return step;
            }

            @Override
            public Map<String, String> resourceAttributes() {
                return Map.of(
                        "service.name",            "jvmobservability",
                        "service.version",         "0.0.1",
                        "deployment.environment",  "local"
                );
            }

            @Override
            public String get(String key) {
                return null; // use built-in defaults for everything else
            }
        };

        return new OtlpMeterRegistry(config, io.micrometer.core.instrument.Clock.SYSTEM);
    }
}
