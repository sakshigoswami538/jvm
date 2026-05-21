package com.jvmobservability.demo.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * ObservabilityConfig — configures the shared MeterRegistry before any metric is registered.
 *
 * Spring Boot 4.x removed MeterRegistryCustomizer from the actuate-autoconfigure package.
 * This class injects MeterRegistry directly and configures it in @PostConstruct,
 * using only pure Micrometer APIs (io.micrometer.core.*) — no Spring Boot version dependency.
 *
 * Three things configured here:
 *   1. Common tags    — stamped on every metric → appear as ES document fields
 *   2. Histograms     — enables p50/p95/p99 buckets for latency metrics in Kibana
 *   3. Cardinality    — drops metrics with too many unique tag combinations
 */
@Configuration
public class ObservabilityConfig {

    private final MeterRegistry registry;

    public ObservabilityConfig(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void configure() {
        registry.config()
                // ── 1. Common tags ────────────────────────────────────────────────────
                // Stamped on every Counter / Timer / Gauge / DistributionSummary.
                // In Elasticsearch each tag becomes a field:
                //   attributes.service = "jvmobservability"
                //   attributes.team    = "backend"
                // In Kibana: filter all dashboard panels by attributes.environment = "local"
                .commonTags(Tags.of(
                        "service",     "jvmobservability",
                        "team",        "backend",
                        "environment", "local"
                ))

                // ── 2. Percentile histograms ──────────────────────────────────────────
                // Without this, OTLP only sends count + sum (mean only).
                // With this, OTLP sends full ExplicitBucketHistogram data
                // → ES stores bucket counts → Kibana can show p50/p95/p99 panels.
                .meterFilter(new MeterFilter() {
                    @Override
                    public DistributionStatisticConfig configure(
                            io.micrometer.core.instrument.Meter.Id id,
                            DistributionStatisticConfig config) {

                        if (id.getName().startsWith("music.song")
                                || id.getName().startsWith("http.server")
                                || id.getName().startsWith("file.upload")
                                || id.getName().startsWith("sim.request")) {

                            return DistributionStatisticConfig.builder()
                                    .percentilesHistogram(true)
                                    .percentiles(0.5, 0.75, 0.95, 0.99)
                                    .serviceLevelObjectives(
                                            Duration.ofMillis(50).toNanos(),
                                            Duration.ofMillis(200).toNanos(),
                                            Duration.ofSeconds(1).toNanos())
                                    .minimumExpectedValue(Duration.ofMillis(1).toNanos())
                                    .maximumExpectedValue(Duration.ofSeconds(30).toNanos())
                                    .expiry(Duration.ofMinutes(5))
                                    .bufferLength(3)
                                    .build()
                                    .merge(config);
                        }
                        return config;
                    }
                })

                // ── 3. Cardinality guard ──────────────────────────────────────────────
                // If music.song.operations.total ever accumulates > 500 unique
                // "route" tag values, deny all new registrations for that metric.
                // Prevents index explosion in Elasticsearch.
                .meterFilter(MeterFilter.maximumAllowableTags(
                        "music.song.operations.total",
                        "route",
                        500,
                        MeterFilter.deny()
                ));
    }
}
