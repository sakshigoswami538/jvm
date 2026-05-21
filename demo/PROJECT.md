# JVM Observability — Music Service

A RESTful Music Service built with **Spring Boot 4 / Java 21** to learn and demonstrate JVM observability through Micrometer + Spring Boot Actuator metrics.

---

## Table of Contents

1. [Technology Stack](#1-technology-stack)
2. [Project Structure](#2-project-structure)
3. [Configuration](#3-configuration)
4. [Data Model](#4-data-model)
5. [REST API Reference](#5-rest-api-reference)
6. [Infrastructure (Docker)](#6-infrastructure-docker)
7. [How to Run](#7-how-to-run)
8. [Actuator & JVM Metrics](#8-actuator--jvm-metrics)
9. [JVM Metric Categories](#9-jvm-metric-categories)
10. [Observability Analysis Guide](#10-observability-analysis-guide)

---

## 1. Technology Stack

| Layer            | Technology                          | Version    |
|------------------|-------------------------------------|------------|
| Language         | Java                                | 21 (LTS)   |
| Framework        | Spring Boot                         | 4.0.3      |
| Web              | Spring MVC (Embedded Tomcat 11)     | —          |
| Database         | MongoDB (Spring Data MongoDB)       | 5.6.3 driver |
| Object Storage   | MinIO (S3-compatible)               | SDK 8.5.17 |
| Metrics          | Micrometer + Spring Boot Actuator   | —          |
| Metrics Export   | Micrometer Prometheus Registry      | —          |
| Build Tool       | Maven (mvnw wrapper)                | —          |

---

## 2. Project Structure

```
demo/
├── pom.xml                                         # Maven dependencies
├── PROJECT.md                                      # This document
├── load-test.sh                                    # Traffic generator script
└── src/main/
    ├── resources/
    │   └── application.properties                  # All config (MongoDB, MinIO, Actuator)
    └── java/com/jvmobservability/demo/
        ├── JvmobservabilityApplication.java        # Main entry point
        ├── config/
        │   └── MinioConfig.java                    # MinioClient Spring bean
        ├── model/
        │   ├── Song.java                           # MongoDB document (Java record)
        │   └── CreateSongRequest.java              # POST body for bulk upload
        ├── repository/
        │   └── SongRepository.java                 # MongoRepository interface
        ├── service/
        │   ├── SongService.java                    # Business logic
        │   └── MinioService.java                   # MinIO upload / delete
        ├── controller/
        │   └── SongController.java                 # REST endpoints + error handlers
        └── exception/
            └── SongNotFoundException.java          # 404 exception
```

---

## 3. Configuration

File: `src/main/resources/application.properties`

### Application
```properties
spring.application.name=jvmobservability
server.port=8080
```

### MongoDB
```properties
spring.mongodb.uri=mongodb://localhost:27017/musicdb
```

### MinIO (Object Storage)
```properties
minio.endpoint=http://localhost:9000
minio.access-key=minioadmin
minio.secret-key=minioadmin123
minio.bucket-name=music

spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=50MB
```

### Spring Boot Actuator
```properties
management.endpoints.web.exposure.include=*
management.endpoint.health.show-details=always
management.endpoints.web.base-path=/actuator
management.prometheus.metrics.export.enabled=true
```

### JVM Metrics (all enabled)
```properties
management.metrics.enable.jvm.memory=true
management.metrics.enable.jvm.gc=true
management.metrics.enable.jvm.threads=true
management.metrics.enable.jvm.classes=true
management.metrics.enable.jvm.buffer=true
management.metrics.enable.jvm.compilation=true
management.metrics.enable.process=true
```

---

## 4. Data Model

### Song (MongoDB Document)

```java
@Document(collection = "songs")
public record Song(@Id String id, String title, String artist, String fileUrl) {}
```

| Field     | Type   | Description                                          |
|-----------|--------|------------------------------------------------------|
| `id`      | String | MongoDB-generated ObjectId (hex string)              |
| `title`   | String | Song title — required, must not be blank             |
| `artist`  | String | Artist name — required, must not be blank            |
| `fileUrl` | String | Full URL to the MP3 in MinIO (null for bulk-test data) |

**Example document in MongoDB:**
```json
{
  "_id": "67d3a1b2c4e5f6a7b8c9d0e1",
  "title": "Kesariya",
  "artist": "Arijit Singh",
  "fileUrl": "http://localhost:9000/music/550e8400-e29b-41d4-a716-Kesariya.mp3"
}
```

---

## 5. REST API Reference

Base URL: `http://localhost:8080`

---

### 5.1 Upload a Song — `POST /api/songs`

Uploads an MP3 file to MinIO and saves metadata to MongoDB.

- **Content-Type:** `multipart/form-data`
- **Parts:**

| Part     | Type | Required | Description       |
|----------|------|----------|-------------------|
| `file`   | File | Yes      | MP3 audio file    |
| `title`  | Text | Yes      | Song title        |
| `artist` | Text | Yes      | Artist name       |

**curl:**
```bash
curl -s -X POST http://localhost:8080/api/songs \
  -F "file=@/c/Users/Sakshi/Downloads/Kesariya Brahmastra 128 Kbps.mp3;type=audio/mpeg" \
  -F "title=Kesariya" \
  -F "artist=Arijit Singh"
```

**Response: `201 Created`**
```json
{
  "id": "67d3a1b2c4e5f6a7b8c9d0e1",
  "title": "Kesariya",
  "artist": "Arijit Singh",
  "fileUrl": "http://localhost:9000/music/550e8400-Kesariya Brahmastra 128 Kbps.mp3"
}
```

**Validation errors:**
- File is empty → `400 Bad Request`
- File is not `audio/mpeg` → `400 Bad Request`
- MinIO unreachable → `500 Internal Server Error`

---

### 5.2 Get All Songs — `GET /api/songs`

Returns all songs stored in MongoDB.

**curl:**
```bash
curl -s http://localhost:8080/api/songs
```

**Response: `200 OK`**
```json
[
  {
    "id": "67d3a1b2c4e5f6a7b8c9d0e1",
    "title": "Kesariya",
    "artist": "Arijit Singh",
    "fileUrl": "http://localhost:9000/music/550e8400-Kesariya.mp3"
  }
]
```

---

### 5.3 Get Song by ID — `GET /api/songs/{id}`

Returns a single song by its MongoDB ID.

**curl:**
```bash
curl -s http://localhost:8080/api/songs/67d3a1b2c4e5f6a7b8c9d0e1
```

**Response: `200 OK`** — single Song JSON

**Error: `404 Not Found`**
```json
{ "error": "Song not found with id: 67d3a1b2c4e5f6a7b8c9d0e1" }
```

---

### 5.4 Delete a Song — `DELETE /api/songs/{id}`

Deletes the MP3 from MinIO **and** the metadata from MongoDB.

**curl:**
```bash
curl -s -X DELETE http://localhost:8080/api/songs/67d3a1b2c4e5f6a7b8c9d0e1 \
  -w "HTTP %{http_code}\n"
```

**Response: `204 No Content`**

**Error: `404 Not Found`** if the ID does not exist.

---

### 5.5 Search Songs by Artist — `GET /api/songs/search?artist=`

Case-insensitive partial match on artist name.

**curl:**
```bash
curl -s "http://localhost:8080/api/songs/search?artist=Arijit"
curl -s "http://localhost:8080/api/songs/search?artist=queen"
```

**Response: `200 OK`** — array of matching Song objects

---

### 5.6 Bulk Upload (Load-Test Helper) — `POST /api/songs/bulk`

Inserts multiple songs without MP3 files. Used to generate traffic for JVM metric analysis. `fileUrl` will be `null` for all records.

**curl:**
```bash
curl -s -X POST http://localhost:8080/api/songs/bulk \
  -H "Content-Type: application/json" \
  -d '[
    {"title":"Bohemian Rhapsody","artist":"Queen"},
    {"title":"Stairway to Heaven","artist":"Led Zeppelin"},
    {"title":"Hotel California","artist":"Eagles"},
    {"title":"Smells Like Teen Spirit","artist":"Nirvana"},
    {"title":"Imagine","artist":"John Lennon"},
    {"title":"Comfortably Numb","artist":"Pink Floyd"},
    {"title":"Purple Haze","artist":"Jimi Hendrix"},
    {"title":"Creep","artist":"Radiohead"},
    {"title":"Like a Rolling Stone","artist":"Bob Dylan"},
    {"title":"Wonderwall","artist":"Oasis"}
  ]'
```

**Response: `201 Created`** — array of 10 saved Song objects

---

### Error Response Format

All errors return a consistent JSON body:

```json
{ "error": "<human-readable message>" }
```

| HTTP Status | Trigger                                   |
|-------------|-------------------------------------------|
| 400         | Blank title/artist, wrong file type       |
| 404         | Song ID not found                         |
| 500         | MinIO/MongoDB connectivity failure        |

---

## 6. Infrastructure (Docker)

All infrastructure runs as Docker containers.

| Container       | Image              | Port            | Purpose                      |
|-----------------|--------------------|-----------------|------------------------------|
| `mongo`         | mongo:7            | 27017:27017     | Song metadata storage        |
| `minio`         | minio/minio        | 9000:9000       | MP3 file object storage      |
| `minio`         | minio/minio        | 9001:9001       | MinIO web console            |

**MinIO credentials:**
```
Access Key : minioadmin
Secret Key : minioadmin123
```

**MinIO Web Console:** `http://localhost:9001`
Login → browse the `music` bucket to see uploaded MP3 files.

**MongoDB connection string:**
```
mongodb://localhost:27017/musicdb
```

---

## 7. How to Run

### Prerequisites
- Java 21
- Docker Desktop running with `mongo` and `minio` containers started
- Maven (or use the included `mvnw` wrapper)

### Start the service
```bash
cd c:/Users/Sakshi/Downloads/jvm/demo
./mvnw spring-boot:run
```

### Verify startup
Look for these lines in the console:
```
Found 1 MongoDB repository interface.
Monitor thread successfully connected to server ... CONNECTED
Exposing 13 endpoints beneath base path '/actuator'
Tomcat started on port 8080
Started JvmobservabilityApplication in ~8 seconds
```

### Kill port 8080 if already in use (Git Bash)
```bash
cmd //c "taskkill /PID $(netstat -ano | grep ':8080' | grep LISTENING | awk '{print $5}') /F"
```

---

## 8. Actuator & JVM Metrics

All Actuator endpoints are available at `http://localhost:8080/actuator/`

| Endpoint                          | Description                                  |
|-----------------------------------|----------------------------------------------|
| `/actuator/health`                | Service health + MongoDB connectivity        |
| `/actuator/metrics`               | List all available metric names              |
| `/actuator/metrics/{metric.name}` | Detail for a single metric                   |
| `/actuator/prometheus`            | All metrics in Prometheus scrape format      |
| `/actuator/info`                  | Application info                             |
| `/actuator/env`                   | Full environment properties                  |

**Quick check — list all available metrics:**
```bash
curl -s http://localhost:8080/actuator/metrics | python -m json.tool
```

---

## 9. JVM Metric Categories

### 9.1 Memory Metrics

```bash
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used
curl -s http://localhost:8080/actuator/metrics/jvm.memory.committed
curl -s http://localhost:8080/actuator/metrics/jvm.memory.max
```

| Metric                  | What it shows                        |
|-------------------------|--------------------------------------|
| `jvm.memory.used`       | Currently used heap + non-heap bytes |
| `jvm.memory.committed`  | Memory committed by the OS to the JVM |
| `jvm.memory.max`        | Maximum memory the JVM can use       |

---

### 9.2 Garbage Collection Metrics

```bash
curl -s http://localhost:8080/actuator/metrics/jvm.gc.pause
curl -s http://localhost:8080/actuator/metrics/jvm.gc.memory.promoted
curl -s http://localhost:8080/actuator/metrics/jvm.gc.memory.allocated
```

| Metric                       | What it shows                         |
|------------------------------|---------------------------------------|
| `jvm.gc.pause`               | GC pause count and total pause time   |
| `jvm.gc.memory.allocated`    | Bytes allocated in Eden per GC cycle  |
| `jvm.gc.memory.promoted`     | Bytes promoted from Young to Old gen  |
| `jvm.gc.overhead`            | % of CPU time spent in GC             |

---

### 9.3 Thread Metrics

```bash
curl -s http://localhost:8080/actuator/metrics/jvm.threads.live
curl -s http://localhost:8080/actuator/metrics/jvm.threads.daemon
curl -s http://localhost:8080/actuator/metrics/jvm.threads.states
```

| Metric                  | What it shows                          |
|-------------------------|----------------------------------------|
| `jvm.threads.live`      | Total live threads                     |
| `jvm.threads.daemon`    | Daemon thread count                    |
| `jvm.threads.peak`      | Highest thread count since JVM start   |
| `jvm.threads.states`    | Threads grouped by state (RUNNABLE etc)|

---

### 9.4 Class Loading Metrics

```bash
curl -s http://localhost:8080/actuator/metrics/jvm.classes.loaded
curl -s http://localhost:8080/actuator/metrics/jvm.classes.unloaded
```

| Metric                   | What it shows                        |
|--------------------------|--------------------------------------|
| `jvm.classes.loaded`     | Currently loaded classes             |
| `jvm.classes.unloaded`   | Total classes unloaded since start   |

---

### 9.5 Buffer Pool Metrics

```bash
curl -s http://localhost:8080/actuator/metrics/jvm.buffer.count
curl -s http://localhost:8080/actuator/metrics/jvm.buffer.memory.used
curl -s http://localhost:8080/actuator/metrics/jvm.buffer.total.capacity
```

| Metric                        | What it shows                          |
|-------------------------------|----------------------------------------|
| `jvm.buffer.count`            | Number of active NIO buffers           |
| `jvm.buffer.memory.used`      | Bytes used by direct/mapped buffers    |
| `jvm.buffer.total.capacity`   | Total capacity of all buffer pools     |

---

### 9.6 JIT Compilation Metrics

```bash
curl -s http://localhost:8080/actuator/metrics/jvm.compilation.time
```

| Metric                   | What it shows                           |
|--------------------------|-----------------------------------------|
| `jvm.compilation.time`   | Total time the JIT compiler has spent   |

---

### 9.7 Process / Runtime Metrics

```bash
curl -s http://localhost:8080/actuator/metrics/process.uptime
curl -s http://localhost:8080/actuator/metrics/process.start.time
curl -s http://localhost:8080/actuator/metrics/process.cpu.usage
curl -s http://localhost:8080/actuator/metrics/system.cpu.usage
```

| Metric                   | What it shows                      |
|--------------------------|------------------------------------|
| `process.uptime`         | Seconds since JVM started          |
| `process.start.time`     | Epoch timestamp of start           |
| `process.cpu.usage`      | CPU used by this JVM process       |
| `system.cpu.usage`       | Total system CPU usage             |

---

### 9.8 Memory Pool Metrics

```bash
# Eden Space (Young Generation — new object allocation)
curl -s "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap&tag=id:G1%20Eden%20Space"

# Old Generation (long-lived objects)
curl -s "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap&tag=id:G1%20Old%20Gen"

# Survivor Space
curl -s "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap&tag=id:G1%20Survivor%20Space"

# Metaspace (class metadata, non-heap)
curl -s "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:nonheap&tag=id:Metaspace"
```

---

### 9.9 MongoDB Driver Metrics (auto-registered)

```bash
curl -s http://localhost:8080/actuator/metrics/mongodb.driver.pool.size
curl -s http://localhost:8080/actuator/metrics/mongodb.driver.pool.checkedout
curl -s http://localhost:8080/actuator/metrics/mongodb.driver.pool.waitqueuesize
curl -s http://localhost:8080/actuator/metrics/mongodb.driver.commands
```

---

## 10. Observability Analysis Guide

### Observation Framework

For each metric, apply this 5-step analysis:

```
1. OBSERVE   → What is the raw value or trend?
2. PATTERN   → Is it steady, growing, spiking, or periodic?
3. INTERPRET → What is the JVM doing internally?
4. STORY     → Translate to a meaningful narrative about service behavior
5. IMPACT    → What effect does this have on CPU / memory / latency / throughput?
```

---

### Story: Upload a Song

**What happens internally when `POST /api/songs` is called:**

1. Tomcat allocates a request thread → `jvm.threads.live` increases
2. The MP3 file bytes are buffered in heap (Eden space) →
   `jvm.memory.used` (Eden) spikes
3. MinIO SDK streams bytes over TCP → `jvm.buffer.memory.used` increases
   (NIO direct buffers used for network I/O)
4. After upload completes, the byte arrays are released → Eden GC eligible
5. MongoDB driver serializes the Song record → minor allocation burst
6. Minor GC fires to reclaim Eden → `jvm.gc.pause` count increments,
   `jvm.gc.memory.allocated` increases
7. Thread is returned to pool → `jvm.threads.live` drops back

---

### Story: Bulk Upload (50 songs)

**What happens internally:**

1. Spring deserializes 50 JSON objects → 50 Song records created in heap
2. `repository.saveAll()` issues 50 MongoDB insert commands in a batch
3. MongoDB connection pool is checked out → `mongodb.driver.pool.checkedout` peaks
4. Large allocation burst in Eden → potentially triggers Minor GC mid-batch
5. GC pause causes a brief latency spike visible in response time
6. After saveAll(), all 50 Song objects become GC roots until response is written
7. Once response is flushed → all 50 objects are eligible for collection

---

### Story: High-Traffic Read (GET /api/songs repeated)

**What happens internally:**

1. Each request deserializes MongoDB BSON documents into Song records
2. Deserialized objects live in Eden for the duration of the request
3. Under high concurrency, Tomcat spawns more threads → `jvm.threads.live` rises
4. Rapid Eden turnover → frequent Minor GC cycles
5. JIT compiler optimizes hot code paths → `jvm.compilation.time` grows
   then plateaus (code is fully compiled)
6. `system.cpu.usage` rises due to GC + JIT activity

---

### Generating Traffic for Analysis

```bash
# Run the load test script (Git Bash)
bash load-test.sh
```

**While load test runs, observe metrics in a second terminal:**

```bash
# Watch memory trend every 5 seconds
watch -n5 'curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | python -m json.tool'

# Watch GC pause count
watch -n5 'curl -s http://localhost:8080/actuator/metrics/jvm.gc.pause | python -m json.tool'

# Watch thread count
watch -n5 'curl -s http://localhost:8080/actuator/metrics/jvm.threads.live | python -m json.tool'
```

**Or pull all metrics in Prometheus format at once:**
```bash
curl -s http://localhost:8080/actuator/prometheus | grep "^jvm_"
curl -s http://localhost:8080/actuator/prometheus | grep "^process_"
```

---

*Last updated: 2026-03-15*
