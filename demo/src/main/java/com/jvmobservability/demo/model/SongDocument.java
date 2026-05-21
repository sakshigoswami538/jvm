package com.jvmobservability.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Elasticsearch document for the "songs" index.
 *
 * Spring Data ES annotations:
 *   @Document  — maps this class to the "songs" ES index (auto-created on startup)
 *   @Id        — maps "id" to the ES document _id field
 *   @MultiField — stores title/artist as both text (analyzed) + keyword (exact/sort)
 *   @Field     — controls ES mapping for each field
 */
@Document(indexName = "songs", createIndex = false)
@Setting(shards = 1, replicas = 0)
public class SongDocument {

    @Id
    private String id;

    // text → full-text search | keyword → exact match + sort
    @MultiField(
        mainField  = @Field(type = FieldType.Text, analyzer = "standard"),
        otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256)
    )
    private String title;

    @MultiField(
        mainField  = @Field(type = FieldType.Text, analyzer = "standard"),
        otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword, ignoreAbove = 256)
    )
    private String artist;

    @Field(type = FieldType.Keyword)
    private String fileUrl;

    // ES date field — Instant maps cleanly to ISO-8601 (date_time format)
    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant createdAt;

    public SongDocument() {}

    public SongDocument(String id, String title, String artist, String fileUrl, Instant createdAt) {
        this.id        = id;
        this.title     = title;
        this.artist    = artist;
        this.fileUrl   = fileUrl;
        this.createdAt = createdAt;
    }

    /** Build a SongDocument from a saved Song record. */
    public static SongDocument from(Song song) {
        return new SongDocument(
                song.id(),
                song.title(),
                song.artist(),
                song.fileUrl(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS)
        );
    }

    public String  getId()        { return id; }
    public String  getTitle()     { return title; }
    public String  getArtist()    { return artist; }
    public String  getFileUrl()   { return fileUrl; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(String id)                 { this.id = id; }
    public void setTitle(String title)           { this.title = title; }
    public void setArtist(String artist)         { this.artist = artist; }
    public void setFileUrl(String fileUrl)       { this.fileUrl = fileUrl; }
    public void setCreatedAt(Instant createdAt)  { this.createdAt = createdAt; }
}
