package com.jvmobservability.demo.repository;

import com.jvmobservability.demo.model.Song;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Spring Data MongoDB repository for Song documents.
 *
 * MongoRepository<Song, String> provides out-of-the-box:
 *   save(song)           → INSERT or UPSERT into "songs" collection
 *   findAll()            → SELECT * FROM songs
 *   findById(id)         → SELECT * WHERE _id = ?
 *   existsById(id)       → SELECT 1 WHERE _id = ?
 *   deleteById(id)       → DELETE WHERE _id = ?
 *   saveAll(list)        → bulk INSERT (single round-trip)
 *   count()              → collection document count
 *
 * JVM observability notes:
 * ─────────────────────────────────────────────────────────────────────
 * • Every Spring Data MongoDB call serialises/deserialises BSON ↔ Java
 *   objects.  This allocates BSON Document objects, byte[] buffers, and
 *   Song records — all short-lived, all on Eden.  Watch
 *   jvm.gc.memory.allocated rise with query frequency.
 *
 * • findAll() and findByArtistContainingIgnoreCase() materialise the
 *   full result set into a List in one shot.  For large collections
 *   this can create significant heap pressure; observable as a spike
 *   in jvm.memory.used{area=heap} followed by a Minor GC.
 *
 * • The MongoDB driver maintains its own connection pool on threads
 *   outside the Tomcat pool.  You will see those threads in
 *   jvm.threads.live even during idle periods.
 * ─────────────────────────────────────────────────────────────────────
 */
public interface SongRepository extends MongoRepository<Song, String> {

    /**
     * Case-insensitive artist search using a MongoDB regex query.
     * Derived query method — Spring Data generates the query:
     *   { "artist": { $regex: "artist", $options: "i" } }
     *
     * Unlike the old in-memory stream scan, this query runs inside
     * MongoDB, reducing heap allocation on the JVM side to just the
     * result list construction.
     */
    // Artist-only search (no pagination)
    List<Song> findByArtistContainingIgnoreCase(String artist);

    // Artist-only search WITH pagination — limits heap allocation on large result sets
    Page<Song> findByArtistContainingIgnoreCase(String artist, Pageable pageable);

    /**
     * Compound search: artist AND title.
     * Uses compound index idx_artist_title {artist:1, title:1}.
     * docsExamined drops dramatically vs artist-only search.
     */
    List<Song> findByArtistContainingIgnoreCaseAndTitleContainingIgnoreCase(String artist, String title);

    // Compound search WITH pagination
    Page<Song> findByArtistContainingIgnoreCaseAndTitleContainingIgnoreCase(
            String artist, String title, Pageable pageable);
}
