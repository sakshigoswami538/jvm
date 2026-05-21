package com.jvmobservability.demo.service;

import com.jvmobservability.demo.model.Song;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IndexScenarioService — 11 MongoDB index observability scenarios.
 *
 *  A  No Index           — COLLSCAN, keysExamined=0, high latency
 *  B  Wrong Index        — idx_title but artist query → COLLSCAN, 0 accessOps
 *  C  Regex Inefficient  — idx_artist + contains regex → IXSCAN, keysExamined very high
 *  D  Optimized (^)      — idx_artist + prefix regex  → IXSCAN, keysExamined low
 *  E  Low Selectivity    — idx_artist + too many matching docs → IXSCAN, docsExamined high
 *  F  Covered Query      — compound {artist,title} + matching projection → docsExamined=0
 *  G  Bad Regex          — idx_artist + suffix regex  → IXSCAN, keysExamined very high
 *  H  Wrong Operator     — idx_artist + $ne           → COLLSCAN (operator bypasses index)
 *  I  Prefix Mismatch    — compound {title,artist}, artist-only query → COLLSCAN
 *  J  Over-Indexing      — 5+ indexes → slow writes, high totalIndexSize
 *  K  Memory Pressure    — 1000 docs + heavy queries → cache hits drop, page faults rise
 *
 * Prometheus metrics (all under index.scenario.*):
 *   index_scenario_count                 — number of indexes on songs
 *   index_scenario_total_size_bytes      — total index memory
 *   index_scenario_artist_access_ops     — cumulative ops on idx_artist
 *   index_scenario_title_access_ops      — cumulative ops on idx_title
 *   index_scenario_query_scan_type       — 0=COLLSCAN, 1=IXSCAN
 *   index_scenario_cache_hit_ratio       — WiredTiger cache hit ratio
 *   index_scenario_docs_examined         — totalDocsExamined from explain
 *   index_scenario_keys_examined         — totalKeysExamined from explain
 *   index_scenario_execution_time_ms     — executionTimeMillis from explain
 *   index_scenario_nreturned             — nReturned from explain
 *   index_scenario_write_latency_ms      — measured write latency (Scenario J)
 */
@Service
public class IndexScenarioService {

    private static final Logger log = LoggerFactory.getLogger(IndexScenarioService.class);

    private static final String COLLECTION       = "songs";
    private static final String IDX_ARTIST       = "idx_artist";
    private static final String IDX_TITLE        = "idx_title";
    private static final String IDX_URL          = "idx_url";
    private static final String IDX_ARTIST_TITLE = "idx_artist_title";
    private static final String IDX_TITLE_ARTIST = "idx_title_artist";

    private static final String[] TEST_ARTISTS = {
            "Arijit Singh", "AR Rahman", "Pritam", "Atif Aslam", "Sonu Nigam"
    };

    private final MongoTemplate mongoTemplate;
    private final MeterRegistry meterRegistry;

    // Current active scenario — drives which explain query is used in refreshMetrics
    private volatile String currentScenario = "A";

    // ── Micrometer backing fields ─────────────────────────────────────────────
    private final AtomicLong gaugeIndexCount      = new AtomicLong(0);
    private final AtomicLong gaugeIndexSizeBytes  = new AtomicLong(0);
    private final AtomicLong gaugeArtistAccessOps = new AtomicLong(0);
    private final AtomicLong gaugeTitleAccessOps  = new AtomicLong(0);
    private final AtomicLong gaugeQueryScanType   = new AtomicLong(0); // 0=COLLSCAN, 1=IXSCAN
    private volatile double  gaugeCacheHitRatio   = 0.0;
    private final AtomicLong gaugeDocsExamined    = new AtomicLong(0);
    private final AtomicLong gaugeKeysExamined    = new AtomicLong(0);
    private final AtomicLong gaugeExecutionTimeMs = new AtomicLong(0);
    private final AtomicLong gaugeNReturned       = new AtomicLong(0);
    private final AtomicLong gaugeWriteLatencyMs  = new AtomicLong(0);

    public IndexScenarioService(MongoTemplate mongoTemplate, MeterRegistry meterRegistry) {
        this.mongoTemplate = mongoTemplate;
        this.meterRegistry = meterRegistry;
    }

    // ── Register Gauges once at startup ──────────────────────────────────────
    @PostConstruct
    public void registerGauges() {
        Gauge.builder("index.scenario.count", gaugeIndexCount, AtomicLong::get)
                .description("Number of indexes on songs collection").register(meterRegistry);

        Gauge.builder("index.scenario.total.size.bytes", gaugeIndexSizeBytes, AtomicLong::get)
                .description("Total index memory in bytes").register(meterRegistry);

        Gauge.builder("index.scenario.artist.access.ops", gaugeArtistAccessOps, AtomicLong::get)
                .description("Cumulative access ops on idx_artist").register(meterRegistry);

        Gauge.builder("index.scenario.title.access.ops", gaugeTitleAccessOps, AtomicLong::get)
                .description("Cumulative access ops on idx_title").register(meterRegistry);

        Gauge.builder("index.scenario.query.scan.type", gaugeQueryScanType, AtomicLong::get)
                .description("0=COLLSCAN, 1=IXSCAN").register(meterRegistry);

        Gauge.builder("index.scenario.cache.hit.ratio", this, s -> s.gaugeCacheHitRatio)
                .description("WiredTiger cache hit ratio 0.0–1.0").register(meterRegistry);

        Gauge.builder("index.scenario.docs.examined", gaugeDocsExamined, AtomicLong::get)
                .description("totalDocsExamined from explain — high means no useful index").register(meterRegistry);

        Gauge.builder("index.scenario.keys.examined", gaugeKeysExamined, AtomicLong::get)
                .description("totalKeysExamined from explain — high with low docsExamined = index used").register(meterRegistry);

        Gauge.builder("index.scenario.execution.time.ms", gaugeExecutionTimeMs, AtomicLong::get)
                .description("executionTimeMillis from explain").register(meterRegistry);

        Gauge.builder("index.scenario.nreturned", gaugeNReturned, AtomicLong::get)
                .description("nReturned from explain").register(meterRegistry);

        Gauge.builder("index.scenario.write.latency.ms", gaugeWriteLatencyMs, AtomicLong::get)
                .description("Measured write latency in ms — high in Scenario J (over-indexing)").register(meterRegistry);

        log.info("IndexScenarioService: 11 Gauges registered under index.scenario.*");
    }

    // ── Scheduled metric refresh every 5 seconds ─────────────────────────────
    @Scheduled(initialDelay = 5000, fixedDelay = 5000)
    public void refreshMetrics() {
        try {
            // collStats → index count + total size
            Document cs = mongoTemplate.executeCommand(new Document("collStats", COLLECTION));
            gaugeIndexCount.set(longOf(cs, "nindexes"));
            gaugeIndexSizeBytes.set(longOf(cs, "totalIndexSize"));

            // $indexStats → per-index access ops
            List<Document> stats = runIndexStats();
            gaugeArtistAccessOps.set(accessOps(stats, IDX_ARTIST));
            gaugeTitleAccessOps.set(accessOps(stats, IDX_TITLE));

            // explain with scenario-specific query
            Map<String, Object> plan = runExplainForScenario();
            String stage = (String) plan.get("stage");
            gaugeQueryScanType.set("IXSCAN".equals(stage) ? 1L : 0L);
            gaugeDocsExamined.set((long) plan.get("totalDocsExamined"));
            gaugeKeysExamined.set((long) plan.get("totalKeysExamined"));
            gaugeExecutionTimeMs.set((long) plan.get("executionTimeMillis"));
            gaugeNReturned.set((long) plan.get("nReturned"));

            // WiredTiger cache hit ratio
            Document wt = getWiredTigerCache();
            if (wt != null) {
                long hits   = longOf(wt, "pages requested from the cache");
                long misses = longOf(wt, "pages read into cache");
                long total  = hits + misses;
                gaugeCacheHitRatio = total > 0 ? (double) hits / total : 1.0;
            }

            // Write latency — measured only in Scenario J (over-indexing)
            if ("J".equals(currentScenario)) {
                gaugeWriteLatencyMs.set(measureWriteLatency());
            }

        } catch (Exception e) {
            log.warn("IndexScenarioService metric refresh failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESET — Drop all custom indexes + remove all load-test documents
    // Call this before starting a fresh test cycle.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> reset() {
        dropAllCustomIndexes();
        long removed = 0;
        removed += mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Load Test Song")),    COLLECTION).getDeletedCount();
        removed += mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Low Selectivity Song")), COLLECTION).getDeletedCount();
        removed += mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Memory Test Song")),  COLLECTION).getDeletedCount();
        removed += mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Write Test")),        COLLECTION).getDeletedCount();
        removed += mongoTemplate.remove(Query.query(Criteria.where("artist").is("TestArtist")),           COLLECTION).getDeletedCount();
        currentScenario = "RESET";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status",         "RESET COMPLETE");
        r.put("loadTestDocsRemoved", removed);
        r.put("remainingDocuments",  mongoTemplate.count(new Query(), COLLECTION));
        r.put("remainingIndexes",    listIndexNames());
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO A — No Index
    // No custom indexes → every artist query does a full COLLSCAN.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioA() {
        currentScenario = "A";
        dropAllCustomIndexes();
        Map<String, Object> r = buildBaseResponse("A", "No Index Created",
                "No artist index. Every search scans ALL documents (COLLSCAN). keysExamined=0.",
                "Create artist index: POST /api/index/scenario/d");
        r.put("indexesAfterSetup", listIndexNames());
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO B — Wrong Index (Index Exists but Not Used)
    // idx_title created but queries filter by artist → COLLSCAN still occurs.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioB() {
        currentScenario = "B";
        dropAllCustomIndexes();
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("title", Sort.Direction.ASC).named(IDX_TITLE));
        Map<String, Object> r = buildBaseResponse("B", "Index Exists but Not Used",
                "idx_title exists but artist queries cannot use it → COLLSCAN. idx_title has 0 accessOps (wasted memory).",
                "Drop idx_title, create idx_artist: POST /api/index/scenario/d");
        r.put("indexesAfterSetup", listIndexNames());
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO C — Regex Inefficient (Contains, No Anchor)
    // idx_artist used (IXSCAN) but contains-regex must scan ALL index entries.
    // keysExamined is very high even though few docs match.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioC() {
        currentScenario = "C";
        dropAllCustomIndexes();
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("artist", Sort.Direction.ASC).named(IDX_ARTIST));
        // 200 extra docs to make the index scan expensive
        List<Song> loadData = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            loadData.add(new Song(null, "Load Test Song " + i,
                    TEST_ARTISTS[i % TEST_ARTISTS.length], null));
        }
        mongoTemplate.insertAll(loadData);
        Map<String, Object> r = buildBaseResponse("C", "Regex Inefficient (Contains Scan)",
                "idx_artist used (IXSCAN) but {$regex:'Arijit'} has no anchor — MongoDB scans EVERY index key. keysExamined very high.",
                "Switch to prefix regex: {$regex:'^Arijit'} to limit index range scan.");
        r.put("indexesAfterSetup", listIndexNames());
        r.put("totalDocuments", mongoTemplate.count(new Query(), COLLECTION));
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO D — Optimized Prefix Regex (^)
    // idx_artist + prefix regex → only the matching range in the index is scanned.
    // keysExamined ≈ nReturned (efficient).
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioD() {
        currentScenario = "D";
        dropAllCustomIndexes();
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("artist", Sort.Direction.ASC).named(IDX_ARTIST));
        // Clean up load-test data from Scenario C/E/K
        mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Load Test Song")), COLLECTION);
        mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Low Selectivity Song")), COLLECTION);
        mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Memory Test Song")), COLLECTION);
        Map<String, Object> r = buildBaseResponse("D", "Optimized Prefix Regex (^)",
                "idx_artist + {$regex:'^Arijit'} — MongoDB scans only the matching index range. keysExamined ≈ nReturned. Optimal.",
                "No action needed. This is the most efficient regex pattern.");
        r.put("indexesAfterSetup", listIndexNames());
        r.put("totalDocuments", mongoTemplate.count(new Query(), COLLECTION));
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO E — Low Selectivity
    // idx_artist used but 200 docs share the same artist value.
    // IXSCAN returns/examines many docs → index provides little selectivity benefit.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioE() {
        currentScenario = "E";
        dropAllCustomIndexes();
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("artist", Sort.Direction.ASC).named(IDX_ARTIST));
        // 200 docs all with the same artist → very low selectivity
        List<Song> lowSel = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            lowSel.add(new Song(null, "Low Selectivity Song " + i, "Arijit Singh", null));
        }
        mongoTemplate.insertAll(lowSel);
        Map<String, Object> r = buildBaseResponse("E", "Low Selectivity Index",
                "idx_artist used (IXSCAN) but 200+ docs share artist='Arijit Singh'. docsExamined is very high. Index barely helps.",
                "Consider compound index {artist,title} or a more selective field.");
        r.put("indexesAfterSetup", listIndexNames());
        r.put("totalDocuments", mongoTemplate.count(new Query(), COLLECTION));
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO F — Covered Query (docsExamined = 0)
    // Compound index {artist,title} covers both filter and projection.
    // MongoDB never reads the document — all data comes from the index itself.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioF() {
        currentScenario = "F";
        dropAllCustomIndexes();
        // Covered index: must include every field in filter AND projection
        Document idx = new Document("artist", 1).append("title", 1);
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new CompoundIndexDefinition(idx).named(IDX_ARTIST_TITLE));
        // Remove bulk data
        mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Load Test Song")), COLLECTION);
        mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Low Selectivity Song")), COLLECTION);
        mongoTemplate.remove(Query.query(Criteria.where("title").regex("^Memory Test Song")), COLLECTION);
        Map<String, Object> r = buildBaseResponse("F", "Covered Query — docsExamined = 0",
                "Compound index {artist,title} covers the query. Projection {artist,title,_id:0} means MongoDB never touches documents. Ultra-fast.",
                "No action needed. Covered query is the absolute best case.");
        r.put("indexesAfterSetup", listIndexNames());
        r.put("requiredProjection", "artist=1, title=1, _id=0  (must exclude _id to be fully covered)");
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO G — Bad Regex (Suffix / No Anchor)
    // idx_artist used but suffix regex "ijit Singh" has no start anchor.
    // MongoDB must scan EVERY index entry to check each value — keysExamined ≫ nReturned.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioG() {
        currentScenario = "G";
        dropAllCustomIndexes();
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("artist", Sort.Direction.ASC).named(IDX_ARTIST));
        Map<String, Object> r = buildBaseResponse("G", "Bad Regex (Suffix / No Anchor)",
                "Query uses {$regex:'ijit Singh'} — no ^ anchor. MongoDB must check every index key. keysExamined is maximum.",
                "Always use an anchored prefix regex: {$regex:'^Arijit'} to restrict index range.");
        r.put("indexesAfterSetup", listIndexNames());
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO H — Wrong Operator ($ne Bypasses Index)
    // idx_artist exists but $ne cannot use an index efficiently.
    // MongoDB reverts to COLLSCAN even with idx_artist present.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioH() {
        currentScenario = "H";
        dropAllCustomIndexes();
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("artist", Sort.Direction.ASC).named(IDX_ARTIST));
        Map<String, Object> r = buildBaseResponse("H", "Wrong Operator ($ne Bypasses Index)",
                "idx_artist exists but {artist: {$ne:'Arijit Singh'}} → COLLSCAN. $ne must examine every document.",
                "Rewrite as $in with allowed values, or restructure data to avoid $ne on high-cardinality fields.");
        r.put("indexesAfterSetup", listIndexNames());
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO I — Compound Index Prefix Mismatch
    // Index is {title:1, artist:1}. Query filters by artist only (not title).
    // MongoDB cannot use a compound index unless the leading prefix field is present.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioI() {
        currentScenario = "I";
        dropAllCustomIndexes();
        // Compound index with WRONG prefix order for artist-only queries
        Document idx = new Document("title", 1).append("artist", 1);
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new CompoundIndexDefinition(idx).named(IDX_TITLE_ARTIST));
        Map<String, Object> r = buildBaseResponse("I", "Compound Index Prefix Mismatch",
                "Index is {title,artist} but query filters by artist only. MongoDB cannot use a compound index without its leading field → COLLSCAN.",
                "Fix index order: create {artist:1,title:1} so artist is the prefix.");
        r.put("indexesAfterSetup", listIndexNames());
        r.put("problem", "idx_title_artist has title as prefix. Artist-only query skips it entirely.");
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO J — Over-Indexing (Slow Writes)
    // 5 indexes on songs collection. Every insert/update must maintain ALL indexes.
    // Reads are fast but writes are measurably slower.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioJ() {
        currentScenario = "J";
        dropAllCustomIndexes();
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("artist", Sort.Direction.ASC).named(IDX_ARTIST));
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("title", Sort.Direction.ASC).named(IDX_TITLE));
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("fileUrl", Sort.Direction.ASC).named(IDX_URL));
        Document comp1 = new Document("artist", 1).append("title", 1);
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new CompoundIndexDefinition(comp1).named(IDX_ARTIST_TITLE));
        Document comp2 = new Document("title", 1).append("artist", 1);
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new CompoundIndexDefinition(comp2).named(IDX_TITLE_ARTIST));

        long writeLatency = measureWriteLatency();
        gaugeWriteLatencyMs.set(writeLatency);

        Map<String, Object> r = buildBaseResponse("J", "Over-Indexing (High Write Latency)",
                "6 total indexes (including _id_). Every write updates all 6 index trees. Write latency increases. totalIndexSize grows.",
                "Audit index usage via $indexStats. Drop indexes with 0 accessOps.");
        r.put("indexesAfterSetup", listIndexNames());
        r.put("measuredWriteLatencyMs", writeLatency);
        r.put("totalIndexSizeBytes", longOf(
                mongoTemplate.executeCommand(new Document("collStats", COLLECTION)), "totalIndexSize"));
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO K — Memory Pressure (Cache Misses + Page Faults)
    // Insert 1000 docs so the working set exceeds WiredTiger's in-memory cache.
    // Heavy queries force disk reads → cache hit ratio drops, page faults rise.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> setupScenarioK() {
        currentScenario = "K";
        dropAllCustomIndexes();
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new Index("artist", Sort.Direction.ASC).named(IDX_ARTIST));
        // 1000 docs to inflate the working set
        List<Song> bulk = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            bulk.add(new Song(null, "Memory Test Song " + i,
                    TEST_ARTISTS[i % TEST_ARTISTS.length], null));
        }
        mongoTemplate.insertAll(bulk);
        Map<String, Object> r = buildBaseResponse("K", "Memory Pressure (Cache Misses / Page Faults)",
                "1000 docs inserted. Run repeated parallel searches to exhaust cache. Watch cacheHitRatio drop and pageFaults rise.",
                "Increase --wiredTigerCacheSizeGB in MongoDB config, or reduce working set by removing stale data.");
        r.put("indexesAfterSetup", listIndexNames());
        r.put("totalDocuments", mongoTemplate.count(new Query(), COLLECTION));
        r.put("tip", "Run 50+ search requests then call GET /api/index/observe to see cache degradation.");
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OBSERVE — Full metrics snapshot for the active scenario
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> observe() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", LocalDateTime.now().toString());
        report.put("activeScenario", currentScenario);

        // Index inventory
        List<String> indexNames = listIndexNames();
        report.put("indexes", indexNames);
        report.put("indexCount", (long) indexNames.size());
        report.put("hasArtistIndex",        indexNames.contains(IDX_ARTIST));
        report.put("hasTitleIndex",         indexNames.contains(IDX_TITLE));
        report.put("hasCompoundArtistTitle", indexNames.contains(IDX_ARTIST_TITLE));
        report.put("hasCompoundTitleArtist", indexNames.contains(IDX_TITLE_ARTIST));

        // collStats
        try {
            Document cs = mongoTemplate.executeCommand(new Document("collStats", COLLECTION));
            report.put("totalIndexSizeBytes", longOf(cs, "totalIndexSize"));
            report.put("totalDocuments",      longOf(cs, "count"));
            Document indexSizes = (Document) cs.get("indexSizes");
            if (indexSizes != null) {
                Map<String, Long> sizeMap = new LinkedHashMap<>();
                indexSizes.forEach((k, v) -> sizeMap.put(k, longOfObj(v)));
                report.put("indexSizePerIndex", sizeMap);
            }
        } catch (Exception e) {
            report.put("collStatsError", e.getMessage());
        }

        // explain() with scenario-appropriate query
        try {
            report.put("queryPlan", runExplainForScenario());
        } catch (Exception e) {
            report.put("explainError", e.getMessage());
        }

        // $indexStats — per-index cumulative access ops
        try {
            List<Document> stats = runIndexStats();
            Map<String, Long> ops = new LinkedHashMap<>();
            for (Document stat : stats) {
                String name       = stat.getString("name");
                Document accesses = (Document) stat.get("accesses");
                ops.put(name, accesses != null ? longOf(accesses, "ops") : 0L);
            }
            report.put("indexAccessOps", ops);
        } catch (Exception e) {
            report.put("indexStatsError", e.getMessage());
        }

        // WiredTiger cache
        try {
            Document wt = getWiredTigerCache();
            if (wt != null) {
                long hits   = longOf(wt, "pages requested from the cache");
                long misses = longOf(wt, "pages read into cache");
                long total  = hits + misses;
                double ratio = total > 0 ? (double) hits / total : 1.0;
                Map<String, Object> cache = new LinkedHashMap<>();
                cache.put("pagesRequestedFromCache", hits);
                cache.put("pagesReadIntoCache",      misses);
                cache.put("cacheHitRatioPercent",    Math.round(ratio * 10000.0) / 100.0);
                report.put("wiredTigerCache", cache);
            }
        } catch (Exception e) {
            report.put("cacheStatsError", e.getMessage());
        }

        // Write latency measurement (Scenario J only)
        if ("J".equals(currentScenario)) {
            report.put("writeLatencyMs", measureWriteLatency());
        }

        // Page faults from serverStatus (most visible in Scenario K)
        try {
            Document ss = mongoTemplate.executeCommand(new Document("serverStatus", 1));
            Document extra = (Document) ss.get("extra_info");
            if (extra != null) {
                report.put("pageFaults", longOf(extra, "page_faults"));
            }
        } catch (Exception ignored) {}

        report.put("observation",       buildObservation(report));
        report.put("recommendedAction", buildAction(currentScenario));
        return report;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Choose the right explain query for the active scenario.
     * The query pattern is the key variable that drives the execution plan difference.
     */
    private Map<String, Object> runExplainForScenario() {
        return switch (currentScenario) {
            // D: prefix regex — efficient index range scan
            case "D" -> runExplainWithQuery(
                    new Document("artist", new Document("$regex", "^Arijit")), null);

            // E: exact equality — low selectivity (many matching docs)
            case "E" -> runExplainWithQuery(
                    new Document("artist", "Arijit Singh"), null);

            // F: prefix regex + covered projection (artist+title, exclude _id)
            case "F" -> runExplainWithQuery(
                    new Document("artist", new Document("$regex", "^Arijit")),
                    new Document("_id", 0).append("artist", 1).append("title", 1));

            // G: suffix/contains regex with no anchor — full index scan
            case "G" -> runExplainWithQuery(
                    new Document("artist", new Document("$regex", "ijit Singh").append("$options", "i")), null);

            // H: $ne operator — bypasses index entirely
            case "H" -> runExplainWithQuery(
                    new Document("artist", new Document("$ne", "Arijit Singh")), null);

            // I: query by artist against compound {title,artist} — prefix mismatch → COLLSCAN
            case "I" -> runExplainWithQuery(
                    new Document("artist", new Document("$regex", "^Arijit")), null);

            // A, B, C, J, K: contains regex (default — shows COLLSCAN vs IXSCAN depending on indexes)
            default -> runExplainWithQuery(
                    new Document("artist", new Document("$regex", "Arijit").append("$options", "i")), null);
        };
    }

    private Map<String, Object> runExplainWithQuery(Document filter, Document projection) {
        Document findCmd = new Document("find", COLLECTION).append("filter", filter);
        if (projection != null) findCmd.append("projection", projection);

        Document cmd = new Document("explain", findCmd).append("verbosity", "executionStats");
        Document result = mongoTemplate.executeCommand(cmd);

        Document queryPlanner = (Document) result.get("queryPlanner");
        Document winningPlan  = queryPlanner != null ? (Document) queryPlanner.get("winningPlan") : null;
        String   stage        = "UNKNOWN";
        String   indexName    = null;

        if (winningPlan != null) {
            stage = winningPlan.getString("stage");
            // IXSCAN is nested inside FETCH or PROJECTION_COVERED
            if ("FETCH".equals(stage) || "PROJECTION_COVERED".equals(stage) || "PROJECTION_DEFAULT".equals(stage)) {
                Document inputStage = (Document) winningPlan.get("inputStage");
                if (inputStage != null) {
                    stage     = inputStage.getString("stage");
                    indexName = inputStage.getString("indexName");
                }
            }
        }

        Document es = (Document) result.get("executionStats");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("stage",               stage);
        plan.put("indexUsed",           indexName);
        plan.put("nReturned",           es != null ? longOf(es, "nReturned")           : 0L);
        plan.put("totalDocsExamined",   es != null ? longOf(es, "totalDocsExamined")   : 0L);
        plan.put("totalKeysExamined",   es != null ? longOf(es, "totalKeysExamined")   : 0L);
        plan.put("executionTimeMillis", es != null ? longOf(es, "executionTimeMillis") : 0L);
        return plan;
    }

    /** Insert one doc, measure round-trip time, then delete it. */
    private long measureWriteLatency() {
        try {
            Song test = new Song(null, "WriteLatencyProbe_" + System.currentTimeMillis(),
                    "TestArtist", null);
            long start = System.currentTimeMillis();
            Song saved = mongoTemplate.save(test, COLLECTION);
            long latency = System.currentTimeMillis() - start;
            mongoTemplate.remove(Query.query(Criteria.where("_id").is(saved.id())), COLLECTION);
            return latency;
        } catch (Exception e) {
            log.warn("Write latency measurement failed: {}", e.getMessage());
            return -1L;
        }
    }

    /** Drop all custom indexes (leaves only _id_). */
    private void dropAllCustomIndexes() {
        for (String name : List.of(IDX_ARTIST, IDX_TITLE, IDX_URL, IDX_ARTIST_TITLE, IDX_TITLE_ARTIST)) {
            dropIndex(name);
        }
    }

    private List<Document> runIndexStats() {
        Document cmd = new Document("aggregate", COLLECTION)
                .append("pipeline", List.of(new Document("$indexStats", new Document())))
                .append("cursor", new Document());
        Document result = mongoTemplate.executeCommand(cmd);
        Document cursor = (Document) result.get("cursor");
        if (cursor == null) return Collections.emptyList();
        Object batch = cursor.get("firstBatch");
        return batch instanceof List ? (List<Document>) batch : Collections.emptyList();
    }

    private Document getWiredTigerCache() {
        Document status = mongoTemplate.executeCommand(new Document("serverStatus", 1));
        Document wt = (Document) status.get("wiredTiger");
        return wt != null ? (Document) wt.get("cache") : null;
    }

    private long accessOps(List<Document> stats, String indexName) {
        return stats.stream()
                .filter(d -> indexName.equals(d.getString("name")))
                .mapToLong(d -> {
                    Document acc = (Document) d.get("accesses");
                    return acc != null ? longOf(acc, "ops") : 0L;
                })
                .findFirst().orElse(0L);
    }

    private List<String> listIndexNames() {
        return mongoTemplate.indexOps(COLLECTION).getIndexInfo()
                .stream().map(info -> info.getName()).toList();
    }

    private void dropIndex(String name) {
        try {
            mongoTemplate.indexOps(COLLECTION).dropIndex(name);
            log.info("Dropped index: {}", name);
        } catch (Exception e) {
            log.debug("Index {} not found (ok to skip): {}", name, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String buildObservation(Map<String, Object> report) {
        Map<String, Object> plan = (Map<String, Object>) report.get("queryPlan");
        String stage      = plan != null ? (String) plan.get("stage")                        : "?";
        long docsExamined = plan != null ? toLong(plan.get("totalDocsExamined"))   : 0L;
        long keysExamined = plan != null ? toLong(plan.get("totalKeysExamined"))   : 0L;
        long nReturned    = plan != null ? toLong(plan.get("nReturned"))           : 0L;
        long execMs       = plan != null ? toLong(plan.get("executionTimeMillis")) : 0L;
        String indexUsed  = plan != null ? (String) plan.get("indexUsed")                    : null;

        return switch (currentScenario) {
            case "A" -> String.format(
                    "SCENARIO A — No index. %s. docsExamined=%d, keysExamined=%d, returned=%d, time=%dms. FIX: create idx_artist.",
                    stage, docsExamined, keysExamined, nReturned, execMs);
            case "B" -> String.format(
                    "SCENARIO B — Wrong index (idx_title). Artist query still %s. docsExamined=%d, keysExamined=%d, time=%dms. idx_title accessOps=0 (wasted).",
                    stage, docsExamined, keysExamined, execMs);
            case "C" -> String.format(
                    "SCENARIO C — Regex inefficient. %s using %s but contains-regex scans ALL keys. keysExamined=%d, docsExamined=%d, time=%dms.",
                    stage, indexUsed, keysExamined, docsExamined, execMs);
            case "D" -> String.format(
                    "SCENARIO D — Optimized prefix regex. %s on %s. keysExamined=%d ≈ nReturned=%d. docsExamined=%d. time=%dms. OPTIMAL.",
                    stage, indexUsed, keysExamined, nReturned, docsExamined, execMs);
            case "E" -> String.format(
                    "SCENARIO E — Low selectivity. %s on %s but too many docs match. docsExamined=%d, keysExamined=%d, returned=%d. time=%dms.",
                    stage, indexUsed, docsExamined, keysExamined, nReturned, execMs);
            case "F" -> String.format(
                    "SCENARIO F — Covered query! %s on %s. docsExamined=%d (should be 0!). keysExamined=%d. time=%dms. BEST POSSIBLE.",
                    stage, indexUsed, docsExamined, keysExamined, execMs);
            case "G" -> String.format(
                    "SCENARIO G — Bad regex (suffix/no anchor). %s on %s. keysExamined=%d (very high!). docsExamined=%d. time=%dms.",
                    stage, indexUsed, keysExamined, docsExamined, execMs);
            case "H" -> String.format(
                    "SCENARIO H — $ne bypasses index. %s (idx_artist ignored). docsExamined=%d, keysExamined=%d. time=%dms.",
                    stage, docsExamined, keysExamined, execMs);
            case "I" -> String.format(
                    "SCENARIO I — Compound prefix mismatch. idx_title_artist can't serve artist-only query → %s. docsExamined=%d. time=%dms.",
                    stage, docsExamined, execMs);
            case "J" -> String.format(
                    "SCENARIO J — Over-indexing. %d total indexes. Reads are %s (fast) but writes are slow. Check writeLatencyMs and totalIndexSizeBytes.",
                    toLong(report.getOrDefault("indexCount", 0L)), stage);
            case "K" -> {
                Map<String, Object> cache = (Map<String, Object>) report.get("wiredTigerCache");
                double ratio = cache != null ? ((Number) cache.get("cacheHitRatioPercent")).doubleValue() : 0.0;
                yield String.format(
                        "SCENARIO K — Memory pressure. cacheHitRatio=%.1f%%, pageFaults=%d. %s. docsExamined=%d. time=%dms.",
                        ratio, toLong(report.getOrDefault("pageFaults", 0L)), stage, docsExamined, execMs);
            }
            default -> "No active scenario. Call POST /api/index/scenario/{a-k} to begin.";
        };
    }

    private String buildAction(String scenario) {
        return switch (scenario) {
            case "A" -> "Create artist index: POST /api/index/scenario/d";
            case "B" -> "Drop idx_title, create idx_artist: POST /api/index/scenario/d";
            case "C" -> "Use prefix regex {$regex:'^Arijit'} — see Scenario D for comparison.";
            case "D" -> "No action needed. Prefix regex with idx_artist is optimal.";
            case "E" -> "Consider compound index {artist,title} for better selectivity.";
            case "F" -> "No action needed. Covered query is the most efficient possible.";
            case "G" -> "Always anchor regex with ^ : {$regex:'^Arijit'} to restrict index scan range.";
            case "H" -> "Rewrite $ne as $in with allowed values, or restructure schema.";
            case "I" -> "Recreate compound index with artist as leading field: {artist:1, title:1}";
            case "J" -> "Run GET /api/index/observe, check indexAccessOps. Drop any index where ops=0.";
            case "K" -> "Increase WiredTiger cache size or reduce dataset. Check /actuator/prometheus for index_scenario_cache_hit_ratio.";
            default  -> "Call POST /api/index/scenario/{a-k} to set up a scenario.";
        };
    }

    private Map<String, Object> buildBaseResponse(String scenario, String name,
                                                   String observation, String action) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario",    scenario);
        r.put("name",        name);
        r.put("observation", observation);
        r.put("action",      action);
        r.put("timestamp",   LocalDateTime.now().toString());
        return r;
    }

    /** Safe cast from any Number (Integer, Long, Double) to long. */
    private long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private long longOf(Document doc, String key) {
        Object val = doc.get(key);
        if (val instanceof Long l)    return l;
        if (val instanceof Integer i) return i.longValue();
        if (val instanceof Double d)  return d.longValue();
        return 0L;
    }

    private long longOfObj(Object val) {
        if (val instanceof Long l)    return l;
        if (val instanceof Integer i) return i.longValue();
        if (val instanceof Double d)  return d.longValue();
        return 0L;
    }
}
