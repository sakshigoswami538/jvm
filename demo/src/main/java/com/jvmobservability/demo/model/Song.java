package com.jvmobservability.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Song document stored in MongoDB, collection "songs".
 *
 * Fields:
 *   id      — MongoDB-generated ObjectId (hex string)
 *   title   — song title (required)
 *   artist  — artist name (required)
 *   fileUrl — full URL to the MP3 stored in MinIO
 *             e.g. http://localhost:9000/music/uuid-filename.mp3
 *             null for bulk test data that has no real file attached.
 *
 * NOTE: Indexes are managed by IndexScenarioService for observability testing.
 *       Do NOT add @CompoundIndex here — it auto-creates on startup and
 *       breaks scenarios that require no indexes (A, B, H, I).
 */
@Document(collection = "songs")
public record Song(@Id String id, String title, String artist, String fileUrl) {

    public Song {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (artist == null || artist.isBlank()) {
            throw new IllegalArgumentException("artist must not be blank");
        }
    }
}
