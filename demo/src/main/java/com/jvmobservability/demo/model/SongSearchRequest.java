package com.jvmobservability.demo.model;

/**
 * All search parameters for GET /api/songs/es/search
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SEARCH TYPE GUIDE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * A. BASIC — Search by Field (exact match on one field)
 *    ?searchType=basic&field=title&value=Ye Raate Ye Mausam
 *    ?searchType=basic&field=artist&value=AGFGHH
 *
 * B. PARTIAL — LIKE search (contains, case-insensitive)
 *    ?searchType=partial&field=title&value=Mausam
 *    ?searchType=partial&field=artist&value=AGF
 *
 * C. FULLTEXT — Full-text across title + artist (relevance scored + fuzzy)
 *    ?searchType=fulltext&q=bajrangi
 *    ?searchType=fulltext&q=ye raate
 *
 * D. MULTIFIELD — Search same value across multiple fields simultaneously
 *    ?searchType=multifield&q=AGFGHH
 *    (checks title, artist in one shot)
 *
 * E. ADVANCED — Combine filters: artist, title, date range, free text
 *    ?searchType=advanced&artist=AGFGHH
 *    ?searchType=advanced&title=Mausam&dateFrom=2026-04-01&dateTo=2026-04-30
 *    ?searchType=advanced&q=love&artist=Arijit&sortBy=createdAt&sortOrder=asc
 *
 * ALL TYPES support sorting + pagination:
 *    &sortBy=title|artist|createdAt|_score
 *    &sortOrder=asc|desc
 *    &page=0&size=10
 */
public class SongSearchRequest {

    private String searchType = "fulltext";  // basic | partial | fulltext | multifield | advanced

    // Basic / Partial — single field search
    private String field;   // title | artist
    private String value;   // the value to match

    // Fulltext / Multifield
    private String q;       // free-text query

    // Advanced filters
    private String artist;
    private String title;
    private String dateFrom;  // ISO-8601: 2026-04-01
    private String dateTo;    // ISO-8601: 2026-04-30

    // Sorting
    private String sortBy    = "_score";   // _score | title | artist | createdAt
    private String sortOrder = "desc";     // asc | desc

    // Pagination
    private int page = 0;
    private int size = 10;

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public String getSearchType()  { return searchType; }
    public void   setSearchType(String v) { this.searchType = v; }

    public String getField()  { return field; }
    public void   setField(String v) { this.field = v; }

    public String getValue()  { return value; }
    public void   setValue(String v) { this.value = v; }

    public String getQ()      { return q; }
    public void   setQ(String v) { this.q = v; }

    public String getArtist()   { return artist; }
    public void   setArtist(String v) { this.artist = v; }

    public String getTitle()    { return title; }
    public void   setTitle(String v) { this.title = v; }

    public String getDateFrom() { return dateFrom; }
    public void   setDateFrom(String v) { this.dateFrom = v; }

    public String getDateTo()   { return dateTo; }
    public void   setDateTo(String v) { this.dateTo = v; }

    public String getSortBy()   { return sortBy; }
    public void   setSortBy(String v) { this.sortBy = v; }

    public String getSortOrder(){ return sortOrder; }
    public void   setSortOrder(String v) { this.sortOrder = v; }

    public int    getPage()     { return page; }
    public void   setPage(int v) { this.page = v; }

    public int    getSize()     { return size; }
    public void   setSize(int v) { this.size = v; }
}
