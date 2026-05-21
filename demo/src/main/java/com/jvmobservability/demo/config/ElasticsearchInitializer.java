package com.jvmobservability.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * ElasticsearchInitializer — Layer 4 setup (runs once on Spring Boot startup).
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  LAYER 4: Elasticsearch                                                   │
 * │                                                                           │
 * │  OTel Collector (elasticsearch exporter)                                  │
 * │      ↓  POST http://elasticsearch:9200/otel-metrics/_bulk                 │
 * │      ↓  JSON documents (one per metric data point per push)               │
 * │                                                                           │
 * │  Document structure (mapping.mode=otel in collector config):              │
 * │  {                                                                        │
 * │    "@timestamp": "2024-01-01T00:00:10.000Z",                             │
 * │    "name": "music.song.upload.total",                                    │
 * │    "description": "Total song upload attempts",                          │
 * │    "unit": "1",                                                          │
 * │    "sum": {                                                              │
 * │      "dataPoints": [{                                                    │
 * │        "startTimeUnixNano": "1704067200000000000",                       │
 * │        "timeUnixNano": "1704067210000000000",                            │
 * │        "asDouble": 42.0,                                                 │
 * │        "attributes": {                                                   │
 * │          "service": "music",                                             │
 * │          "environment": "local"                                          │
 * │        }                                                                 │
 * │      }]                                                                  │
 * │    },                                                                    │
 * │    "resource.attributes.service.name": "jvmobservability"               │
 * │  }                                                                       │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * What this class does:
 *   1. Waits for Elasticsearch to be reachable (retries up to 10x / 30s).
 *   2. PUT _index_template/otel-metrics-template — controls how otel-metrics
 *      documents are stored, mapped, and retained.
 *   3. Non-fatal: if ES is unreachable (e.g. dev without Docker), it logs a
 *      warning and continues — the app still works, metrics just go to Prometheus.
 */
@Component
public class ElasticsearchInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchInitializer.class);

    @Value("${elasticsearch.url:http://localhost:9200}")
    private String elasticsearchUrl;

    // The index template body — applied to every index matching otel-metrics*
    // Key settings explained inline below.
    private static final String INDEX_TEMPLATE = """
            {
              "index_patterns": ["otel-metrics*"],
              "template": {
                "settings": {
                  "number_of_shards":   1,
                  "number_of_replicas": 0,
                  "index.refresh_interval": "10s",
                  "index.codec": "best_compression",
                  "index.mapping.total_fields.limit": 2000
                },
                "mappings": {
                  "dynamic_templates": [
                    {
                      "strings_as_keyword": {
                        "match_mapping_type": "string",
                        "mapping": { "type": "keyword" }
                      }
                    }
                  ],
                  "properties": {
                    "@timestamp":  { "type": "date"    },
                    "name":        { "type": "keyword" },
                    "description": { "type": "keyword", "index": false },
                    "unit":        { "type": "keyword" },
                    "sum.dataPoints.asDouble":              { "type": "double"  },
                    "sum.dataPoints.startTimeUnixNano":     { "type": "long"    },
                    "sum.dataPoints.timeUnixNano":          { "type": "long"    },
                    "gauge.dataPoints.asDouble":            { "type": "double"  },
                    "histogram.dataPoints.sum":             { "type": "double"  },
                    "histogram.dataPoints.count":           { "type": "long"    },
                    "histogram.dataPoints.min":             { "type": "double"  },
                    "histogram.dataPoints.max":             { "type": "double"  }
                  }
                }
              },
              "priority": 100
            }
            """;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[OTel Pipeline] Initializing Elasticsearch index template at {}", elasticsearchUrl);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // ── Step 1: Wait for Elasticsearch to be reachable ──────────────────
        boolean esReady = waitForElasticsearch(client);
        if (!esReady) {
            log.warn("[OTel Pipeline] Elasticsearch not reachable at {} — skipping index template setup. " +
                     "Metrics will still push to OTel Collector (Prometheus path works). " +
                     "Start Elasticsearch to enable Kibana visualization.", elasticsearchUrl);
            return;
        }

        // ── Step 2: Create / update the index template ───────────────────────
        applyIndexTemplate(client);

        // ── Step 3: Confirm otel-metrics index exists (first push may not have happened yet)
        logIndexStatus(client);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Polls GET / until Elasticsearch responds 200.
     * Retries every 3s for up to 30s (10 attempts).
     */
    private boolean waitForElasticsearch(HttpClient client) {
        HttpRequest healthCheck = HttpRequest.newBuilder()
                .uri(URI.create(elasticsearchUrl + "/_cluster/health"))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();

        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                HttpResponse<String> resp = client.send(healthCheck, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    log.info("[OTel Pipeline] Elasticsearch is ready (attempt {}/10). Status: {}",
                            attempt, extractStatus(resp.body()));
                    return true;
                }
            } catch (Exception e) {
                log.debug("[OTel Pipeline] Elasticsearch not yet ready (attempt {}/10): {}", attempt, e.getMessage());
            }

            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * PUT _index_template/otel-metrics-template with our mapping/settings.
     *
     * Settings explained:
     *   number_of_replicas: 0      → no replica shards (single-node dev)
     *   refresh_interval: "10s"    → matches OTLP push interval; avoids
     *                                 constant micro-flushes that hurt write throughput
     *   codec: best_compression    → metric names repeat; compression saves ~60-70%
     *   total_fields.limit: 2000   → OTel native mapping creates many dynamic fields
     *   strings_as_keyword          → metric labels are always exact-match searches;
     *                                 keyword is 10x faster than analyzed text for this
     */
    private void applyIndexTemplate(HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchUrl + "/_index_template/otel-metrics-template"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(INDEX_TEMPLATE))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                log.info("[OTel Pipeline] ✓ Index template 'otel-metrics-template' applied successfully. " +
                         "All future otel-metrics* documents will use optimized time-series mappings.");
            } else {
                log.warn("[OTel Pipeline] Index template PUT returned HTTP {}. Body: {}",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("[OTel Pipeline] Failed to apply index template: {}", e.getMessage());
        }
    }

    /**
     * Logs current otel-metrics index stats so you can confirm documents
     * are arriving from the OTel Collector.
     *
     * Look for this in your startup logs:
     *   [OTel Pipeline] otel-metrics index: docs=240, size=1.2mb
     */
    private void logIndexStatus(HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(elasticsearchUrl + "/_cat/indices/otel-metrics*?h=index,docs.count,store.size&format=json"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body().trim();
                if (body.equals("[]") || body.isEmpty()) {
                    log.info("[OTel Pipeline] otel-metrics index does not exist yet — " +
                             "it will be created automatically when the first metric batch arrives " +
                             "from OTel Collector (~10s after app startup).");
                } else {
                    log.info("[OTel Pipeline] Existing otel-metrics indices: {}", body);
                }
            }
        } catch (Exception e) {
            log.debug("[OTel Pipeline] Could not query index status: {}", e.getMessage());
        }
    }

    private String extractStatus(String clusterHealthBody) {
        // Quick extract of "status":"green/yellow/red" without JSON library dependency
        int idx = clusterHealthBody.indexOf("\"status\":\"");
        if (idx == -1) return "unknown";
        int start = idx + 10;
        int end   = clusterHealthBody.indexOf('"', start);
        return end > start ? clusterHealthBody.substring(start, end) : "unknown";
    }
}
