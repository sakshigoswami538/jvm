package com.jvmobservability.demo.service;

import com.jvmobservability.demo.exception.SongNotFoundException;
import com.jvmobservability.demo.metrics.SongMetrics;
import com.jvmobservability.demo.model.Song;
import com.jvmobservability.demo.model.SongDocument;
import com.jvmobservability.demo.model.SongSearchRequest;
import com.jvmobservability.demo.model.SongSearchResult;
import com.jvmobservability.demo.repository.SongEsRepository;
import com.jvmobservability.demo.repository.SongRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SongService — business logic layer.
 *
 * Storage:
 *   MongoDB  → SongRepository (all CRUD)
 *   MinIO    → MinioService   (MP3 files)
 *   ES CRUD  → SongEsRepository (Spring Data ES JPA-style)
 *   ES Search → SongEsRepository @Query methods (A–D)
 *              + ElasticsearchOperations StringQuery (E: advanced — dynamic query)
 */
@Service
public class SongService {

    private static final Logger log = LoggerFactory.getLogger(SongService.class);

    private final SongRepository         repository;
    private final SongEsRepository       songEsRepository;         // Spring Data ES — all ES ops
    private final ElasticsearchOperations elasticsearchOperations;  // advanced search only
    private final MinioService           minioService;
    private final SongMetrics            songMetrics;

    public SongService(SongRepository repository,
                       SongEsRepository songEsRepository,
                       ElasticsearchOperations elasticsearchOperations,
                       MinioService minioService,
                       SongMetrics songMetrics) {
        this.repository               = repository;
        this.songEsRepository         = songEsRepository;
        this.elasticsearchOperations  = elasticsearchOperations;
        this.minioService             = minioService;
        this.songMetrics              = songMetrics;
    }

    // ── 1. Sync upload ────────────────────────────────────────────────────────
    public Song uploadSong(MultipartFile file, String title, String artist) {
        songMetrics.recordUploadStarted(file.getSize());
        long start = System.nanoTime();
        boolean success = false;
        try {
            String fileUrl = minioService.uploadFile(file);
            Song saved     = repository.save(new Song(null, title, artist, fileUrl));

            try {
                SongDocument doc = SongDocument.from(saved);
                log.debug("[ES] Attempting to index song id={} doc={}", saved.id(), doc);
                SongDocument indexed = songEsRepository.save(doc);
                log.info("[ES] Indexed song id={} title='{}' esId={}", saved.id(), title,
                        indexed != null ? indexed.getId() : "null");
            } catch (Exception esEx) {
                log.error("[ES] Failed to index song id={} title='{}': {}", saved.id(), title, esEx.getMessage(), esEx);
            }

            success = true;
            return saved;
        } catch (Exception e) {
            log.error("[ES] Upload failed for title='{}': {}", title, e.getMessage());
            throw e;
        } finally {
            songMetrics.recordUploadFinished(System.nanoTime() - start, success);
        }
    }

    // ── 2. Async upload ───────────────────────────────────────────────────────
    @Async
    public CompletableFuture<Song> uploadSongAsync(MultipartFile file, String title, String artist) {
        songMetrics.recordUploadStarted(file.getSize());
        long start = System.nanoTime();
        boolean success = false;
        try {
            String fileUrl = minioService.uploadFile(file);
            Song saved = repository.save(new Song(null, title, artist, fileUrl));
            try {
                songEsRepository.save(SongDocument.from(saved));
                log.info("[ES] Async indexed song id={} title='{}'", saved.id(), title);
            } catch (Exception esEx) {
                log.error("[ES] Async failed to index song id={}: {}", saved.id(), esEx.getMessage(), esEx);
            }
            success = true;
            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            songMetrics.recordUploadFinished(System.nanoTime() - start, success);
        }
    }

    // ── 3. Get all (MongoDB) ──────────────────────────────────────────────────
    public List<Song> getAllSongs() {
        return repository.findAll();
    }

    // ── 4. Get by ID (MongoDB) ────────────────────────────────────────────────
    public Song getSongById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new SongNotFoundException(id));
    }

    // ── 5. Update — MongoDB + ES ──────────────────────────────────────────────
    public Song updateSong(String id, String title, String artist) {
        Song existing = repository.findById(id)
                .orElseThrow(() -> new SongNotFoundException(id));
        Song updated = new Song(existing.id(), title, artist, existing.fileUrl());
        Song saved = repository.save(updated);
        try {
            songEsRepository.save(SongDocument.from(saved));
            log.info("[ES] Updated song id={} title='{}' artist='{}'", id, title, artist);
        } catch (Exception e) {
            log.error("[ES] Failed to update song id={} in ES: {}", id, e.getMessage(), e);
        }
        return saved;
    }

    // ── 6. Delete — MongoDB + MinIO + ES ─────────────────────────────────────
    public void deleteSong(String id) {
        Song song = repository.findById(id)
                .orElseThrow(() -> new SongNotFoundException(id));

        if (song.fileUrl() != null) {
            minioService.deleteFile(song.fileUrl());
        }
        repository.deleteById(id);
        songEsRepository.deleteById(id);    // ES delete via Spring Data
        log.info("[ES] Deleted song id={}", id);
        songMetrics.recordDelete();
    }

    // ── 6a. MongoDB search by artist ─────────────────────────────────────────
    public List<Song> searchByArtist(String artist) {
        long start = System.nanoTime();
        List<Song> results = repository.findByArtistContainingIgnoreCase(artist);
        songMetrics.recordSearch(System.nanoTime() - start, "artist");
        return results;
    }

    // ── 6b. MongoDB search by artist — paged ─────────────────────────────────
    public Page<Song> searchByArtistPaged(String artist, int page, int size) {
        long start = System.nanoTime();
        Page<Song> result = repository.findByArtistContainingIgnoreCase(
                artist, PageRequest.of(page, size));
        songMetrics.recordSearch(System.nanoTime() - start, "artist-paged");
        return result;
    }

    // ── 6c. MongoDB search artist + title ────────────────────────────────────
    public List<Song> searchByArtistAndTitle(String artist, String title) {
        long start = System.nanoTime();
        List<Song> results = repository.findByArtistContainingIgnoreCaseAndTitleContainingIgnoreCase(artist, title);
        songMetrics.recordSearch(System.nanoTime() - start, "artist+title");
        return results;
    }

    // ── 6d. MongoDB search artist + title — paged ────────────────────────────
    public Page<Song> searchByArtistAndTitlePaged(String artist, String title, int page, int size) {
        long start = System.nanoTime();
        Page<Song> result = repository.findByArtistContainingIgnoreCaseAndTitleContainingIgnoreCase(
                artist, title, PageRequest.of(page, size));
        songMetrics.recordSearch(System.nanoTime() - start, "artist+title-paged");
        return result;
    }

    // ── 7. Elasticsearch search — routes to @Query repository methods ─────────
    public SongSearchResult searchInElasticsearch(SongSearchRequest req) {
        long start = System.nanoTime();
        SongSearchResult result = switch (req.getSearchType().toLowerCase()) {
            case "basic"      -> basicSearch(req);
            case "partial"    -> partialSearch(req);
            case "multifield" -> multifieldSearch(req);
            case "advanced"   -> advancedSearch(req);
            default           -> fulltextSearch(req);   // "fulltext"
        };
        songMetrics.recordSearch(System.nanoTime() - start, "es-" + req.getSearchType());
        return result;
    }

    // ── 8. Bulk upload — MongoDB + ES ────────────────────────────────────────
    public List<Song> bulkUpload(List<Song> songs) {
        List<Song> saved = repository.saveAll(songs);
        songEsRepository.saveAll(saved.stream().map(SongDocument::from).toList());
        log.info("[ES] Bulk indexed {} songs", saved.size());
        return saved;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SEARCH IMPLEMENTATIONS
    // ═════════════════════════════════════════════════════════════════════════

    // A. BASIC — exact match via @Query term on .keyword field
    private SongSearchResult basicSearch(SongSearchRequest req) {
        Pageable pageable = buildPageable(req);
        String field = resolveField(req.getField());
        String value = req.getValue() != null ? req.getValue() : "";

        Page<SongDocument> page = "artist".equals(field)
                ? songEsRepository.basicSearchByArtist(value, pageable)
                : songEsRepository.basicSearchByTitle(value, pageable);

        log.info("[ES] basic field={} value={} → hits={}", field, value, page.getTotalElements());
        return fromPage(page, req);
    }

    // B. PARTIAL — LIKE '%value%' via @Query wildcard on .keyword field
    private SongSearchResult partialSearch(SongSearchRequest req) {
        Pageable pageable = buildPageable(req);
        String field = resolveField(req.getField());
        String value = req.getValue() != null ? req.getValue() : "";

        Page<SongDocument> page = "artist".equals(field)
                ? songEsRepository.partialSearchByArtist(value, pageable)
                : songEsRepository.partialSearchByTitle(value, pageable);

        log.info("[ES] partial field={} value={} → hits={}", field, value, page.getTotalElements());
        return fromPage(page, req);
    }

    // C. FULLTEXT — multi_match best_fields via @Query
    private SongSearchResult fulltextSearch(SongSearchRequest req) {
        Pageable pageable = buildPageable(req);
        String q = req.getQ() != null ? req.getQ() : "";

        Page<SongDocument> page = songEsRepository.fulltextSearch(q, pageable);
        log.info("[ES] fulltext q='{}' → hits={}", q, page.getTotalElements());
        return fromPage(page, req);
    }

    // D. MULTIFIELD — cross_fields multi_match via @Query
    private SongSearchResult multifieldSearch(SongSearchRequest req) {
        Pageable pageable = buildPageable(req);
        String q = req.getQ() != null ? req.getQ() : "";

        Page<SongDocument> page = songEsRepository.multifieldSearch(q, pageable);
        log.info("[ES] multifield q='{}' → hits={}", q, page.getTotalElements());
        return fromPage(page, req);
    }

    // E. ADVANCED — dynamic bool query (optional: artist, title, dateFrom, dateTo, q)
    // Uses StringQuery + ElasticsearchOperations because @Query cannot handle
    // conditional query clauses (some params may be absent).
    private SongSearchResult advancedSearch(SongSearchRequest req) {
        String queryJson = buildAdvancedQuery(req);
        Pageable pageable = buildPageable(req);

        StringQuery query = new StringQuery(queryJson, pageable);
        SearchHits<SongDocument> hits = elasticsearchOperations.search(query, SongDocument.class);

        List<SongDocument> songs = hits.stream().map(SearchHit::getContent).toList();
        log.info("[ES] advanced → totalHits={} returned={}", hits.getTotalHits(), songs.size());
        return new SongSearchResult("advanced", hits.getTotalHits(),
                req.getPage(), req.getSize(), songs);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Convert a Spring Data ES Page into a SongSearchResult. */
    private SongSearchResult fromPage(Page<SongDocument> page, SongSearchRequest req) {
        return new SongSearchResult(req.getSearchType(), page.getTotalElements(),
                req.getPage(), req.getSize(), page.getContent());
    }

    /** Build Pageable with optional sort. Score-based → no sort (ES default). */
    private Pageable buildPageable(SongSearchRequest req) {
        if (req.getSortBy() == null || "_score".equalsIgnoreCase(req.getSortBy())) {
            return PageRequest.of(req.getPage(), req.getSize());
        }
        Sort.Direction dir = "asc".equalsIgnoreCase(req.getSortOrder())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        String field = switch (req.getSortBy()) {
            case "title"     -> "title.keyword";
            case "artist"    -> "artist.keyword";
            default          -> "createdAt";
        };
        return PageRequest.of(req.getPage(), req.getSize(), Sort.by(dir, field));
    }

    /** "title" or "artist" — defaults to "title" for anything else. */
    private String resolveField(String field) {
        if (field == null || field.isBlank()) return "title";
        return "artist".equalsIgnoreCase(field) ? "artist" : "title";
    }

    /**
     * Build ES bool query JSON for advanced search.
     * Only includes clauses for non-null/non-blank parameters.
     */
    private String buildAdvancedQuery(SongSearchRequest req) {
        List<String> must   = new ArrayList<>();
        List<String> filter = new ArrayList<>();

        if (hasValue(req.getQ())) {
            must.add("""
                    { "multi_match": { "query": "%s", "fields": ["title^2", "artist"], "fuzziness": "AUTO" } }
                    """.formatted(esc(req.getQ())));
        }
        if (hasValue(req.getArtist())) {
            must.add("""
                    { "match": { "artist": { "query": "%s", "operator": "and" } } }
                    """.formatted(esc(req.getArtist())));
        }
        if (hasValue(req.getTitle())) {
            must.add("""
                    { "match": { "title": { "query": "%s", "operator": "and" } } }
                    """.formatted(esc(req.getTitle())));
        }
        if (hasValue(req.getDateFrom()) || hasValue(req.getDateTo())) {
            StringBuilder range = new StringBuilder("{ \"range\": { \"createdAt\": {");
            if (hasValue(req.getDateFrom())) range.append("\"gte\":\"").append(req.getDateFrom()).append("\"");
            if (hasValue(req.getDateFrom()) && hasValue(req.getDateTo())) range.append(",");
            if (hasValue(req.getDateTo()))   range.append("\"lte\":\"").append(req.getDateTo()).append("\"");
            range.append("} } }");
            filter.add(range.toString());
        }

        if (must.isEmpty() && filter.isEmpty()) {
            return "{ \"match_all\": {} }";
        }

        StringBuilder bool = new StringBuilder("{ \"bool\": {");
        if (!must.isEmpty())   bool.append(" \"must\": [").append(String.join(",", must)).append("]");
        if (!filter.isEmpty()) {
            if (!must.isEmpty()) bool.append(",");
            bool.append(" \"filter\": [").append(String.join(",", filter)).append("]");
        }
        bool.append(" } }");
        return bool.toString();
    }

    private boolean hasValue(String s) { return s != null && !s.isBlank(); }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
