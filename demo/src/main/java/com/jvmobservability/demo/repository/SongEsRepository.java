package com.jvmobservability.demo.repository;

import com.jvmobservability.demo.model.SongDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Elasticsearch repository for the "songs" index.
 *
 * ElasticsearchRepository auto-provides:
 *   save()       → PUT /songs/_doc/{id}
 *   saveAll()    → POST /songs/_bulk
 *   deleteById() → DELETE /songs/_doc/{id}
 *   findById()   → GET /songs/_doc/{id}
 *
 * @Query methods below implement the 5 custom search types using raw ES query JSON.
 * All methods accept a Pageable for sort + pagination support.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SEARCH TYPES
 * ─────────────────────────────────────────────────────────────────────────────
 * A. BASIC      — term: exact match on .keyword sub-field (case-insensitive)
 * B. PARTIAL    — wildcard: LIKE '%value%' on .keyword sub-field
 * C. FULLTEXT   — multi_match best_fields: fuzzy, relevance-ranked, title^2
 * D. MULTIFIELD — multi_match cross_fields: words can span title AND artist
 * E. ADVANCED   — bool: dynamic must+filter (handled in SongService via StringQuery)
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Repository
public interface SongEsRepository extends ElasticsearchRepository<SongDocument, String> {

    // ── A. BASIC SEARCH ───────────────────────────────────────────────────────
    // Exact match on title field (.keyword = not analyzed → exact comparison)
    // ?searchType=basic&field=title&value=Ye Raate Ye Mausam
    @Query("""
            { "term": { "title.keyword": { "value": "?0", "case_insensitive": true } } }
            """)
    Page<SongDocument> basicSearchByTitle(String value, Pageable pageable);

    // Exact match on artist field
    // ?searchType=basic&field=artist&value=AGFGHH
    @Query("""
            { "term": { "artist.keyword": { "value": "?0", "case_insensitive": true } } }
            """)
    Page<SongDocument> basicSearchByArtist(String value, Pageable pageable);

    // ── B. PARTIAL SEARCH ─────────────────────────────────────────────────────
    // Wildcard LIKE '%value%' — case-insensitive substring match on title
    // ?searchType=partial&field=title&value=Mausam
    @Query("""
            { "wildcard": { "title.keyword": { "value": "*?0*", "case_insensitive": true } } }
            """)
    Page<SongDocument> partialSearchByTitle(String value, Pageable pageable);

    // Wildcard LIKE '%value%' on artist
    // ?searchType=partial&field=artist&value=AGF
    @Query("""
            { "wildcard": { "artist.keyword": { "value": "*?0*", "case_insensitive": true } } }
            """)
    Page<SongDocument> partialSearchByArtist(String value, Pageable pageable);

    // ── C. FULLTEXT SEARCH ────────────────────────────────────────────────────
    // multi_match best_fields: relevance-ranked, fuzzy, title boosted 2x
    // ?searchType=fulltext&q=bajrangi   (default searchType)
    @Query("""
            {
              "multi_match": {
                "query":         "?0",
                "fields":        ["title^2", "artist"],
                "type":          "best_fields",
                "fuzziness":     "AUTO",
                "prefix_length": 1,
                "operator":      "or"
              }
            }
            """)
    Page<SongDocument> fulltextSearch(String q, Pageable pageable);

    // ── D. MULTIFIELD SEARCH ──────────────────────────────────────────────────
    // multi_match cross_fields: query words can span title AND artist
    // ?searchType=multifield&q=AGFGHH Mausam
    @Query("""
            {
              "multi_match": {
                "query":    "?0",
                "fields":   ["title", "artist"],
                "type":     "cross_fields",
                "operator": "and"
              }
            }
            """)
    Page<SongDocument> multifieldSearch(String q, Pageable pageable);

    // ── E. ADVANCED SEARCH ────────────────────────────────────────────────────
    // Uses dynamic bool query (artist + title + date range + free text — all optional).
    // Cannot use @Query here because the query structure changes based on which
    // parameters are present. Handled in SongService using StringQuery directly.
}
