package com.jvmobservability.demo.model;

import java.util.List;

/**
 * Unified response wrapper for all ES search types.
 *
 * Example response:
 * {
 *   "searchType": "advanced",
 *   "totalHits": 25,
 *   "page": 0,
 *   "size": 10,
 *   "totalPages": 3,
 *   "songs": [ { "id": "...", "title": "...", "artist": "...", ... } ]
 * }
 */
public class SongSearchResult {

    private String             searchType;
    private long               totalHits;
    private int                page;
    private int                size;
    private int                totalPages;
    private List<SongDocument> songs;

    public SongSearchResult(String searchType, long totalHits,
                            int page, int size, List<SongDocument> songs) {
        this.searchType  = searchType;
        this.totalHits   = totalHits;
        this.page        = page;
        this.size        = size;
        this.totalPages  = size > 0 ? (int) Math.ceil((double) totalHits / size) : 0;
        this.songs       = songs;
    }

    public String             getSearchType() { return searchType; }
    public long               getTotalHits()  { return totalHits; }
    public int                getPage()       { return page; }
    public int                getSize()       { return size; }
    public int                getTotalPages() { return totalPages; }
    public List<SongDocument> getSongs()      { return songs; }
}
