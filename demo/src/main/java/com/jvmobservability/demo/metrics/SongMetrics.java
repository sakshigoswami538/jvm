package com.jvmobservability.demo.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SongMetrics — Layer 1 business metrics facade.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  OTel Pipeline — what happens when you call recordUploadStarted():      │
 * │                                                                         │
 * │  1. SongMetrics.recordUploadStarted(fileSize)                           │
 * │       → uploadTotal.increment()         (Micrometer Counter)            │
 * │       → uploadSizeBytes.record(bytes)   (Micrometer DistributionSummary)│
 * │       → activeUploads.incrementAndGet() (AtomicInt backing a Gauge)     │
 * │                                                                         │
 * │  2. MeterRegistry (OtlpMeterRegistry) collects deltas every 10s        │
 * │       → serializes to OTLP ExportMetricsServiceRequest (Protobuf)       │
 * │                                                                         │
 * │  3. HTTP POST http://localhost:4318/v1/metrics                          │
 * │       Content-Type: application/x-protobuf                              │
 * │       Body: binary protobuf (ResourceMetrics → ScopeMetrics → Metric)   │
 * │                                                                         │
 * │  4. OTel Collector receives → memory_limiter → batch → two exporters:   │
 * │       ├── prometheus exporter → :8889/metrics (Grafana)                 │
 * │       └── elasticsearch exporter → POST :9200/otel-metrics/_bulk        │
 * │                                                                         │
 * │  5. Elasticsearch stores one document per metric data point:             │
 * │       {                                                                  │
 * │         "@timestamp": "2024-01-01T00:00:10Z",                           │
 * │         "name": "music.song.upload.total",                              │
 * │         "sum.dataPoints[0].asDouble": 42.0,                             │
 * │         "resource.attributes.service.name": "jvmobservability"          │
 * │       }                                                                  │
 * │                                                                         │
 * │  6. Kibana queries otel-metrics index → Golden Signals dashboard         │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Metric naming convention (OTel semantic conventions):
 *   {domain}.{noun}.{measurement}
 *   e.g. music.song.upload.duration  (seconds, measured by Timer)
 *        music.song.upload.size.bytes (bytes, measured by DistributionSummary)
 *        music.song.upload.total     (count,  measured by Counter)
 *
 * Kibana field reference (otel-metrics index, mapping.mode=otel):
 * ─────────────────────────────────────────────────────────────────────
 *  Metric name                       ES field for value
 * ─────────────────────────────────────────────────────────────────────
 *  music.song.upload.total           sum.dataPoints.asDouble
 *  music.song.upload.failures        sum.dataPoints.asDouble
 *  music.song.delete.total           sum.dataPoints.asDouble
 *  music.song.search.total           sum.dataPoints.asDouble
 *  music.song.upload.duration        histogram.dataPoints.sum / .count
 *  music.song.search.duration        histogram.dataPoints.sum / .count
 *  music.song.upload.size.bytes      histogram.dataPoints.sum / .count
 *  music.song.upload.active          gauge.dataPoints.asDouble
 *  jvm.memory.used                   gauge.dataPoints.asDouble
 *  http.server.requests              histogram.dataPoints.*
 * ─────────────────────────────────────────────────────────────────────
 */
@Component
public class SongMetrics {

    // ─────────────────────────────────────────────────────────────────────────
    // COUNTERS — cumulative, monotonically increasing.
    // OTel type: Sum (monotonic=true, aggregationTemporality=DELTA)
    // In Elasticsearch: sum.dataPoints[].asDouble
    // In Kibana: use "Derivative" agg to get rate (uploads/sec)
    // ─────────────────────────────────────────────────────────────────────────

    /** Every upload attempt, success or failure. */
    private final Counter uploadTotal;

    /** Failed uploads — divide by uploadTotal for error ratio. */
    private final Counter uploadFailures;

    /** Songs deleted from MinIO + MongoDB. */
    private final Counter deleteTotal;

    /** Search queries executed against MongoDB. */
    private final Counter searchTotal;

    // ─────────────────────────────────────────────────────────────────────────
    // TIMERS — measures latency as histogram + explicit percentile gauges.
    // OTel type: Histogram (ExplicitBucketHistogram)
    // In Elasticsearch: histogram.dataPoints[].sum / count / buckets
    // In Kibana: avg(histogram.dataPoints.sum) / avg(histogram.dataPoints.count)
    //            = mean latency (or use percentile gauge metrics for p99)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * End-to-end upload time: MinIO stream + MongoDB document save.
     *
     * What it measures (wall-clock nanos between recordUploadStarted and
     * recordUploadFinished): includes network I/O to MinIO, BSON serialize,
     * and MongoDB round-trip.
     *
     * Kibana panel: "Upload p99 latency" → filter name=music.song.upload.duration,
     * Y-axis = max(gauge.dataPoints.asDouble) where phi=0.99 (the percentile gauge)
     */
    private final Timer uploadDuration;

    /**
     * MongoDB query time for artist / compound search.
     * Use this to detect slow query regressions when index strategy changes.
     */
    private final Timer searchDuration;

    // ─────────────────────────────────────────────────────────────────────────
    // DISTRIBUTION SUMMARY — like a Timer but for non-time values.
    // OTel type: Histogram
    // In Elasticsearch: histogram.dataPoints[].sum / count
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Raw byte size of each uploaded file.
     * Use to alert if average upload size grows unexpectedly (e.g. wrong file type).
     */
    private final DistributionSummary uploadSizeBytes;

    // ─────────────────────────────────────────────────────────────────────────
    // GAUGES — instantaneous value (not cumulative, not rate).
    // OTel type: Gauge
    // In Elasticsearch: gauge.dataPoints[].asDouble
    // In Kibana: "Saturation" panel — max value approaching limit = alarm
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Uploads currently in-flight (started but not yet finished).
     * When this approaches the semaphore limit (50) → expect 429 responses.
     * This is the SATURATION signal in the Golden Signals model.
     */
    private final AtomicInteger activeUploads = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────────────────

    public SongMetrics(MeterRegistry registry) {

        // ── Counters ──────────────────────────────────────────────────────────

        this.uploadTotal = Counter.builder("music.song.upload.total")
                .description("Total song upload attempts (success + failure)")
                .tag("service", "music")
                .register(registry);

        this.uploadFailures = Counter.builder("music.song.upload.failures")
                .description("Song uploads that failed (MinIO stream error or MongoDB save error)")
                .tag("service", "music")
                .register(registry);

        this.deleteTotal = Counter.builder("music.song.delete.total")
                .description("Total songs deleted from MinIO and MongoDB")
                .tag("service", "music")
                .register(registry);

        this.searchTotal = Counter.builder("music.song.search.total")
                .description("Total search queries executed against MongoDB")
                .tag("service", "music")
                .register(registry);

        // ── Timers ────────────────────────────────────────────────────────────
        // publishPercentileHistogram() → full bucket data to Elasticsearch
        // publishPercentiles()         → lightweight gauge per phi (p50, p95, p99)
        // ObservabilityConfig.histogramConfig() adds SLO buckets (50ms, 200ms, 1s)

        this.uploadDuration = Timer.builder("music.song.upload.duration")
                .description("End-to-end upload time: MinIO file transfer + MongoDB document save")
                .tag("service", "music")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.searchDuration = Timer.builder("music.song.search.duration")
                .description("MongoDB query execution time for song search operations")
                .tag("service", "music")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // ── Distribution Summary ──────────────────────────────────────────────

        this.uploadSizeBytes = DistributionSummary.builder("music.song.upload.size.bytes")
                .description("Raw byte size of each uploaded MP3 file")
                .baseUnit("bytes")
                .tag("content.type", "audio/mpeg")
                .publishPercentileHistogram()
                .register(registry);

        // ── Gauge ─────────────────────────────────────────────────────────────
        // Gauge.builder needs a supplier — it reads from the AtomicInteger lazily
        // on every Micrometer scrape/push cycle (every 10s via OTLP).

        Gauge.builder("music.song.upload.active", activeUploads, AtomicInteger::get)
                .description("Upload operations currently in-flight — saturation signal")
                .tag("service", "music")
                .register(registry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called by SongService (and SongController for rejections)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Call at the very start of every upload (sync or async).
     *
     * Records: uploadTotal +1, uploadSizeBytes observation, activeUploads +1
     * Always pair with {@link #recordUploadFinished(long, boolean)}.
     */
    public void recordUploadStarted(long fileSizeBytes) {
        uploadTotal.increment();
        uploadSizeBytes.record(fileSizeBytes);
        activeUploads.incrementAndGet();
    }

    /**
     * Call when upload completes — success or failure.
     *
     * Records: uploadDuration (always), uploadFailures +1 (if !success), activeUploads -1
     *
     * @param durationNanos  System.nanoTime() delta from upload start
     * @param success        true = MinIO + MongoDB both succeeded
     */
    public void recordUploadFinished(long durationNanos, boolean success) {
        uploadDuration.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        activeUploads.decrementAndGet();
        if (!success) {
            uploadFailures.increment();
        }
    }

    /**
     * Call after each successful delete (MinIO + MongoDB).
     */
    public void recordDelete() {
        deleteTotal.increment();
    }

    /**
     * Call after each search query returns.
     *
     * @param durationNanos  wall-clock time of the MongoDB query
     * @param searchType     "artist" | "artist+title" | "paged" — kept as log, not tag,
     *                       to avoid cardinality explosion
     */
    public void recordSearch(long durationNanos, String searchType) {
        searchTotal.increment();
        searchDuration.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        // searchType is intentionally NOT used as a tag — it would create 4 metric streams
        // (artist, artist+title, paged, compound+paged) but risks becoming unbounded.
        // Log it instead for tracing correlation.
    }
}
