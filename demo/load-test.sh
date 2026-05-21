#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# load-test.sh  —  Traffic generator for the JVM Observability Music Service
#
# Usage:
#   chmod +x load-test.sh
#   ./load-test.sh
#
# What this script does (in order):
#   Phase 1 — Bulk upload 50 songs  → fills Eden, may trigger Minor GC
#   Phase 2 — Rapid GET all songs   → creates many short-lived ArrayList objects
#   Phase 3 — Concurrent searches   → stream pipeline allocations, CPU pressure
#   Phase 4 — Mixed read + write     → realistic workload
#   Phase 5 — Bulk delete           → turns live objects into garbage
#   Phase 6 — Idle cooldown         → lets GC run; observe heap reclaim
#
# Keep /actuator/metrics open in another terminal while this runs:
#   watch -n 2 'curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | python -m json.tool'
# ─────────────────────────────────────────────────────────────────────────────

BASE="http://localhost:8080/api/songs"
SEPARATOR="─────────────────────────────────────────────────────────────"

# ── Helpers ──────────────────────────────────────────────────────────────────
post_song() {
  local title="$1" artist="$2"
  curl -s -X POST "$BASE" \
    -H "Content-Type: application/json" \
    -d "{\"title\":\"$title\",\"artist\":\"$artist\"}" | python -m json.tool 2>/dev/null || echo "(response not JSON)"
}

get_all() {
  curl -s "$BASE" | python -m json.tool 2>/dev/null | head -40
}

search_artist() {
  local artist="$1"
  curl -s "$BASE/search?artist=$(python -c "import urllib.parse; print(urllib.parse.quote('$artist'))")" \
    | python -m json.tool 2>/dev/null | head -20
}

delete_song() {
  local id="$1"
  curl -s -X DELETE "$BASE/$id" -w " → HTTP %{http_code}\n"
}

metric() {
  local name="$1"
  echo "  📊 $name:"
  curl -s "http://localhost:8080/actuator/metrics/$name" | python -m json.tool 2>/dev/null | grep -E '"value"|"statistic"' | head -10
}

# ── Phase 0: Verify service is up ─────────────────────────────────────────────
echo ""
echo "$SEPARATOR"
echo "  Checking service health …"
echo "$SEPARATOR"
curl -s http://localhost:8080/actuator/health | python -m json.tool 2>/dev/null || {
  echo "ERROR: service is not running on port 8080"
  echo "Start it with:  cd demo && ./mvnw spring-boot:run"
  exit 1
}

# ── Phase 1: Bulk upload — fill Eden, trigger Minor GC ────────────────────────
echo ""
echo "$SEPARATOR"
echo "  PHASE 1 — Bulk upload 50 songs"
echo "  Watch: jvm.gc.memory.allocated ↑  jvm.gc.pause ↑"
echo "$SEPARATOR"

ARTISTS=("The Beatles" "Pink Floyd" "Led Zeppelin" "David Bowie" "Radiohead")
TITLES=("Song" "Track" "Tune" "Melody" "Harmony" "Rhythm" "Beat" "Sound" "Note" "Chord")

BULK_BODY="["
for i in $(seq 1 50); do
  ARTIST="${ARTISTS[$((RANDOM % 5))]}"
  TITLE="${TITLES[$((RANDOM % 10))]} $i"
  if [ $i -lt 50 ]; then
    BULK_BODY+="{\"title\":\"$TITLE\",\"artist\":\"$ARTIST\"},"
  else
    BULK_BODY+="{\"title\":\"$TITLE\",\"artist\":\"$ARTIST\"}"
  fi
done
BULK_BODY+="]"

echo "Posting bulk 50 songs …"
curl -s -X POST "$BASE/bulk" \
  -H "Content-Type: application/json" \
  -d "$BULK_BODY" | python -m json.tool 2>/dev/null | head -30
echo "…"

sleep 2
echo ""
echo "JVM snapshot after bulk upload:"
metric "jvm.memory.used"
metric "jvm.gc.memory.allocated"

# ── Phase 2: Rapid GET all ─────────────────────────────────────────────────────
echo ""
echo "$SEPARATOR"
echo "  PHASE 2 — 30 rapid GET /api/songs"
echo "  Watch: jvm.gc.memory.allocated ↑ (ArrayList per call)"
echo "$SEPARATOR"

for i in $(seq 1 30); do
  curl -s "$BASE" > /dev/null
done
echo "30 GET-all requests fired."

sleep 1
echo ""
echo "JVM snapshot after GET-all loop:"
metric "jvm.memory.used"
metric "jvm.threads.live"

# ── Phase 3: Concurrent search — CPU + allocation pressure ────────────────────
echo ""
echo "$SEPARATOR"
echo "  PHASE 3 — 20 concurrent artist searches"
echo "  Watch: jvm.threads.live ↑  jvm.gc.memory.allocated ↑"
echo "$SEPARATOR"

for artist in "Beatles" "Floyd" "Zeppelin" "Bowie" "Radio"; do
  for _ in $(seq 1 4); do
    curl -s "$BASE/search?artist=$artist" > /dev/null &
  done
done
wait
echo "20 concurrent search requests completed."

sleep 1
echo ""
echo "JVM snapshot after concurrent search:"
metric "jvm.threads.states"
metric "jvm.gc.memory.allocated"

# ── Phase 4: Mixed workload ────────────────────────────────────────────────────
echo ""
echo "$SEPARATOR"
echo "  PHASE 4 — Mixed reads + individual writes (20 cycles)"
echo "  Watch: steady jvm.gc.memory.allocated  moderate thread activity"
echo "$SEPARATOR"

SONG_IDS=()
for i in $(seq 1 20); do
  RESP=$(post_song "Live Track $i" "Live Artist $((i % 5 + 1))")
  ID=$(echo "$RESP" | python -c "import sys, json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
  [ -n "$ID" ] && SONG_IDS+=("$ID")
  curl -s "$BASE" > /dev/null
  curl -s "$BASE/search?artist=Artist" > /dev/null
done
echo "Mixed phase done. Captured ${#SONG_IDS[@]} new song IDs."

sleep 1

# ── Phase 5: Bulk delete — produce garbage, trigger GC ────────────────────────
echo ""
echo "$SEPARATOR"
echo "  PHASE 5 — Delete ${#SONG_IDS[@]} songs from Phase 4"
echo "  Watch: jvm.memory.used{area=heap} ↓  jvm.gc.pause (Minor GC)"
echo "$SEPARATOR"

for ID in "${SONG_IDS[@]}"; do
  delete_song "$ID" > /dev/null
done
echo "All Phase-4 songs deleted."

sleep 2
echo ""
echo "JVM snapshot after bulk delete (wait for GC):"
metric "jvm.memory.used"
metric "jvm.gc.pause"

# ── Phase 6: Idle cooldown — let GC reclaim heap ──────────────────────────────
echo ""
echo "$SEPARATOR"
echo "  PHASE 6 — 10 s idle cooldown"
echo "  Watch: jvm.memory.used{area=heap} slowly decreasing"
echo "$SEPARATOR"
sleep 10

echo ""
echo "JVM snapshot after cooldown:"
metric "jvm.memory.used"
metric "jvm.gc.memory.promoted"
metric "jvm.compilation.time"

# ── Final metric dump ─────────────────────────────────────────────────────────
echo ""
echo "$SEPARATOR"
echo "  FINAL JVM METRICS SUMMARY"
echo "$SEPARATOR"

for m in \
  "jvm.memory.used" \
  "jvm.memory.committed" \
  "jvm.memory.max" \
  "jvm.gc.memory.allocated" \
  "jvm.gc.memory.promoted" \
  "jvm.gc.pause" \
  "jvm.threads.live" \
  "jvm.threads.daemon" \
  "jvm.threads.peak" \
  "jvm.threads.states" \
  "jvm.classes.loaded" \
  "jvm.classes.unloaded" \
  "jvm.buffer.count" \
  "jvm.buffer.memory.used" \
  "jvm.compilation.time" \
  "process.uptime" \
  "process.cpu.usage"; do
  metric "$m"
  echo ""
done

echo ""
echo "Load test complete. See the observability guide below:"
echo "  http://localhost:8080/actuator/metrics          (all metric names)"
echo "  http://localhost:8080/actuator/prometheus       (Prometheus format)"
echo "  http://localhost:8080/actuator/health           (service health)"
echo ""
