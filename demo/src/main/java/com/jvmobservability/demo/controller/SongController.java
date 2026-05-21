package com.jvmobservability.demo.controller;

import com.jvmobservability.demo.exception.SongNotFoundException;
import com.jvmobservability.demo.metrics.SongMetrics;
import com.jvmobservability.demo.model.CreateSongRequest;
import com.jvmobservability.demo.model.Song;
import com.jvmobservability.demo.model.SongDocument;
import com.jvmobservability.demo.model.SongSearchRequest;
import com.jvmobservability.demo.model.SongSearchResult;
import com.jvmobservability.demo.service.SongService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SongController — REST entry point (Layer 1 of OTel pipeline).
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  Controller-level metrics (HTTP layer)                                    │
 * │                                                                           │
 * │  AUTO-INSTRUMENTED by Micrometer (no code needed):                       │
 * │    http.server.requests  — counter per {uri, method, status}             │
 * │                            → ES field: sum.dataPoints.asDouble           │
 * │    http.server.requests  — timer (histogram) per {uri, method, status}   │
 * │                            → ES field: histogram.dataPoints.*            │
 * │                                                                           │
 * │  CUSTOM metrics (defined here):                                           │
 * │    http.server.active_requests — gauge: concurrent in-flight requests    │
 * │                                   → saturation signal in Kibana          │
 * │    upload.rejected.total       — 429 rejections from semaphore gate      │
 * │    upload.semaphore.available  — remaining async upload capacity         │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Endpoint summary:
 *   POST   /api/songs              Upload song (multipart: file + title + artist)
 *   GET    /api/songs              Get all songs
 *   GET    /api/songs/{id}         Get song by ID
 *   DELETE /api/songs/{id}         Delete song (MinIO + MongoDB)
 *   GET    /api/songs/search       Search by artist (+ optional title, pagination)
 *   POST   /api/songs/async        Async upload (returns 202, semaphore-gated)
 *   POST   /api/songs/bulk         Bulk metadata upload (load-test helper)
 */
@RestController
@RequestMapping("/api/songs")
public class SongController {

    private final SongService songService;
    private final SongMetrics songMetrics;

    // ── Concurrency gate for async uploads ────────────────────────────────────
    // When full → HTTP 429 (backpressure).
    // Semaphore slots used visible in Kibana via upload.semaphore.available gauge.
    private final Semaphore uploadSemaphore = new Semaphore(50);

    // ── Controller-level custom metrics ──────────────────────────────────────

    /**
     * 429 responses issued because the async semaphore was exhausted.
     * ES document name: "upload.rejected.total"
     * Kibana: rate(sum.dataPoints.asDouble) over time = rejections/sec
     */
    private final Counter uploadRejectedCounter;

    /**
     * In-flight HTTP requests across ALL endpoints (not just uploads).
     * Incremented when request enters any handler, decremented in finally.
     * ES document name: "http.server.active_requests"
     * Kibana: gauge.dataPoints.asDouble — saturation panel
     */
    private final AtomicInteger activeHttpRequests = new AtomicInteger(0);

    public SongController(SongService songService,
                          SongMetrics songMetrics,
                          MeterRegistry meterRegistry) {
        this.songService  = songService;
        this.songMetrics  = songMetrics;

        // ── upload.rejected.total ─────────────────────────────────────────────
        this.uploadRejectedCounter = Counter.builder("upload.rejected.total")
                .description("HTTP 429 responses — async upload slot exhausted (semaphore full)")
                .tag("endpoint", "/api/songs/async")
                .tag("reason",   "semaphore_full")
                .register(meterRegistry);

        // ── upload.semaphore.available ────────────────────────────────────────
        // When this approaches 0, the next async request gets a 429.
        // Kibana: alert when gauge < 5 (fewer than 5 slots remain).
        Gauge.builder("upload.semaphore.available", uploadSemaphore, Semaphore::availablePermits)
                .description("Remaining async upload slots (max 50). Nearing 0 = overload.")
                .tag("endpoint", "/api/songs/async")
                .register(meterRegistry);

        // ── http.server.active_requests ───────────────────────────────────────
        // All HTTP handlers increment/decrement this via trackRequest().
        Gauge.builder("http.server.active_requests", activeHttpRequests, AtomicInteger::get)
                .description("Number of HTTP requests currently being processed (all endpoints)")
                .tag("service", "jvmobservability")
                .register(meterRegistry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Sync upload — POST /api/songs
    //
    //    Metrics in Kibana after this call:
    //      music.song.upload.total +1          (Counter)
    //      music.song.upload.duration record   (Timer/Histogram → p99 panel)
    //      music.song.upload.size.bytes record (DistributionSummary)
    //      music.song.upload.active: 1 → 0    (Gauge)
    //      http.server.requests [auto]          (Micrometer auto-instrumentation)
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Song> uploadSong(
            @RequestParam("file")   MultipartFile file,
            @RequestParam("title")  String title,
            @RequestParam("artist") String artist) {

        validateFile(file);
        activeHttpRequests.incrementAndGet();
        try {
            Song saved = songService.uploadSong(file, title, artist);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } finally {
            activeHttpRequests.decrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Get all songs — GET /api/songs
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<Song>> getAllSongs() {
        activeHttpRequests.incrementAndGet();
        try {
            return ResponseEntity.ok(songService.getAllSongs());
        } finally {
            activeHttpRequests.decrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Get by ID — GET /api/songs/{id}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Song> getSongById(@PathVariable String id) {
        activeHttpRequests.incrementAndGet();
        try {
            return ResponseEntity.ok(songService.getSongById(id));
        } finally {
            activeHttpRequests.decrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Update — PUT /api/songs/{id}
    //    Body: { "title": "...", "artist": "..." }
    //    Updates MongoDB + re-indexes in Elasticsearch.
    // ─────────────────────────────────────────────────────────────────────────
    @PutMapping("/{id}")
    public ResponseEntity<Song> updateSong(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String title  = body.get("title");
        String artist = body.get("artist");
        if (title == null || title.isBlank())  throw new IllegalArgumentException("title must not be blank");
        if (artist == null || artist.isBlank()) throw new IllegalArgumentException("artist must not be blank");

        activeHttpRequests.incrementAndGet();
        try {
            return ResponseEntity.ok(songService.updateSong(id, title, artist));
        } finally {
            activeHttpRequests.decrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Delete — DELETE /api/songs/{id}
    //    Metric: music.song.delete.total +1
    // ─────────────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSong(@PathVariable String id) {
        activeHttpRequests.incrementAndGet();
        try {
            songService.deleteSong(id);
            return ResponseEntity.noContent().build();
        } finally {
            activeHttpRequests.decrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Search — GET /api/songs/search?artist=...&title=...&page=0&size=10
    //    Metric: music.song.search.total +1, music.song.search.duration record
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String artist,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        activeHttpRequests.incrementAndGet();
        try {
            boolean paged    = page != null && size != null;
            boolean compound = title != null && !title.isBlank();

            if (paged && compound)  return ResponseEntity.ok(songService.searchByArtistAndTitlePaged(artist, title, page, size));
            if (paged)              return ResponseEntity.ok(songService.searchByArtistPaged(artist, page, size));
            if (compound)           return ResponseEntity.ok(songService.searchByArtistAndTitle(artist, title));
            return ResponseEntity.ok(songService.searchByArtist(artist));
        } finally {
            activeHttpRequests.decrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Async upload — POST /api/songs/async
    //
    //    Semaphore-gated: if 50 uploads already in-flight → HTTP 429.
    //    Metric: upload.rejected.total +1 on 429.
    //    Metric: upload.semaphore.available drops toward 0 under load.
    //
    //    The 429 rate in Kibana is your SATURATION indicator:
    //    ES query: name="upload.rejected.total" → rate over time
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping(value = "/async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<ResponseEntity<Song>> uploadSongAsync(
            @RequestParam("file")   MultipartFile file,
            @RequestParam("title")  String title,
            @RequestParam("artist") String artist) {

        validateFile(file);

        if (!uploadSemaphore.tryAcquire()) {
            // ⑤ Emit: uploadRejectedCounter +1
            uploadRejectedCounter.increment();
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).<Song>build());
        }

        activeHttpRequests.incrementAndGet();
        return songService.uploadSongAsync(file, title, artist)
                .thenApply(saved -> ResponseEntity.status(HttpStatus.ACCEPTED).body(saved))
                .whenComplete((result, error) -> {
                    uploadSemaphore.release();
                    activeHttpRequests.decrementAndGet();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Elasticsearch Search — GET /api/songs/es/search
    //
    //  A. BASIC   — exact match on one field
    //     ?searchType=basic&field=title&value=Ye Raate Ye Mausam
    //     ?searchType=basic&field=artist&value=AGFGHH
    //
    //  B. PARTIAL — LIKE '%value%' on one field
    //     ?searchType=partial&field=title&value=Mausam
    //     ?searchType=partial&field=artist&value=AGF
    //
    //  C. FULLTEXT — fuzzy, relevance-ranked, across title+artist (DEFAULT)
    //     ?q=bajrangi
    //     ?q=ye raate&sortBy=title&sortOrder=asc
    //
    //  D. MULTIFIELD — cross-field: words can span title AND artist
    //     ?searchType=multifield&q=AGFGHH Mausam
    //
    //  E. ADVANCED — combine artist, title, dateFrom, dateTo, q
    //     ?searchType=advanced&artist=AGFGHH
    //     ?searchType=advanced&title=Mausam&dateFrom=2026-04-01&dateTo=2026-04-30
    //     ?searchType=advanced&q=love&sortBy=createdAt&sortOrder=desc&page=0&size=5
    //
    //  ALL: &sortBy=_score|title|artist|createdAt  &sortOrder=asc|desc
    //       &page=0  &size=10
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/es/search")
    public ResponseEntity<SongSearchResult> esSearch(
            @RequestParam(defaultValue = "fulltext") String searchType,
            @RequestParam(required = false)          String q,
            @RequestParam(required = false)          String field,
            @RequestParam(required = false)          String value,
            @RequestParam(required = false)          String artist,
            @RequestParam(name = "songTitle", required = false) String songTitle,
            @RequestParam(required = false)          String dateFrom,
            @RequestParam(required = false)          String dateTo,
            @RequestParam(defaultValue = "_score")   String sortBy,
            @RequestParam(defaultValue = "desc")     String sortOrder,
            @RequestParam(defaultValue = "0")        int    page,
            @RequestParam(defaultValue = "10")       int    size) {

        activeHttpRequests.incrementAndGet();
        try {
            SongSearchRequest req = new SongSearchRequest();
            req.setSearchType(searchType);
            req.setQ(q);
            req.setField(field);
            req.setValue(value);
            req.setArtist(artist);
            req.setTitle(songTitle);      // mapped from "songTitle" to avoid Spring collision
            req.setDateFrom(dateFrom);
            req.setDateTo(dateTo);
            req.setSortBy(sortBy);
            req.setSortOrder(sortOrder);
            req.setPage(page);
            req.setSize(size);
            return ResponseEntity.ok(songService.searchInElasticsearch(req));
        } finally {
            activeHttpRequests.decrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Bulk metadata upload — POST /api/songs/bulk (load-test helper)
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/bulk")
    public ResponseEntity<List<Song>> bulkUpload(@RequestBody List<CreateSongRequest> requests) {
        activeHttpRequests.incrementAndGet();
        try {
            List<Song> input = requests.stream()
                    .map(r -> new Song(null, r.title(), r.artist(), null))
                    .toList();
            return ResponseEntity.status(HttpStatus.CREATED).body(songService.bulkUpload(input));
        } finally {
            activeHttpRequests.decrementAndGet();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("audio/mpeg")) {
            throw new IllegalArgumentException(
                    "only MP3 files accepted (audio/mpeg), received: " + contentType);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exception handlers
    // ─────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(SongNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(SongNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleServerError(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage()));
    }
}
