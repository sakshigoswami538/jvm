package com.jvmobservability.demo.controller;

import com.jvmobservability.demo.service.IndexScenarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * IndexScenarioController — 11 index observability scenarios.
 *
 * Setup endpoints (POST):
 *   /api/index/scenario/a  — No Index (COLLSCAN)
 *   /api/index/scenario/b  — Wrong Index (idx_title, artist query → COLLSCAN)
 *   /api/index/scenario/c  — Regex Inefficient (contains, high keysExamined)
 *   /api/index/scenario/d  — Optimized Prefix Regex (^ anchor, efficient)
 *   /api/index/scenario/e  — Low Selectivity (many docs same artist)
 *   /api/index/scenario/f  — Covered Query (docsExamined = 0)
 *   /api/index/scenario/g  — Bad Regex (suffix, very high keysExamined)
 *   /api/index/scenario/h  — Wrong Operator ($ne bypasses index)
 *   /api/index/scenario/i  — Compound Prefix Mismatch ({title,artist} vs artist query)
 *   /api/index/scenario/j  — Over-Indexing (slow writes)
 *   /api/index/scenario/k  — Memory Pressure (cache misses, page faults)
 *
 * Observe endpoint (GET):
 *   /api/index/observe     — Full snapshot: explain + accessOps + cache + pageFaults
 *
 * Typical flow for each scenario:
 *   1. POST /api/index/scenario/{letter}
 *   2. GET  /api/songs/search?artist=Arijit Singh  (run 5–10 times)
 *   3. GET  /api/index/observe
 */
@RestController
@RequestMapping("/api/index")
public class IndexScenarioController {

    private final IndexScenarioService service;

    public IndexScenarioController(IndexScenarioService service) {
        this.service = service;
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        return ResponseEntity.ok(service.reset());
    }

    @PostMapping("/scenario/a")
    public ResponseEntity<Map<String, Object>> scenarioA() {
        return ResponseEntity.ok(service.setupScenarioA());
    }

    @PostMapping("/scenario/b")
    public ResponseEntity<Map<String, Object>> scenarioB() {
        return ResponseEntity.ok(service.setupScenarioB());
    }

    @PostMapping("/scenario/c")
    public ResponseEntity<Map<String, Object>> scenarioC() {
        return ResponseEntity.ok(service.setupScenarioC());
    }

    @PostMapping("/scenario/d")
    public ResponseEntity<Map<String, Object>> scenarioD() {
        return ResponseEntity.ok(service.setupScenarioD());
    }

    @PostMapping("/scenario/e")
    public ResponseEntity<Map<String, Object>> scenarioE() {
        return ResponseEntity.ok(service.setupScenarioE());
    }

    @PostMapping("/scenario/f")
    public ResponseEntity<Map<String, Object>> scenarioF() {
        return ResponseEntity.ok(service.setupScenarioF());
    }

    @PostMapping("/scenario/g")
    public ResponseEntity<Map<String, Object>> scenarioG() {
        return ResponseEntity.ok(service.setupScenarioG());
    }

    @PostMapping("/scenario/h")
    public ResponseEntity<Map<String, Object>> scenarioH() {
        return ResponseEntity.ok(service.setupScenarioH());
    }

    @PostMapping("/scenario/i")
    public ResponseEntity<Map<String, Object>> scenarioI() {
        return ResponseEntity.ok(service.setupScenarioI());
    }

    @PostMapping("/scenario/j")
    public ResponseEntity<Map<String, Object>> scenarioJ() {
        return ResponseEntity.ok(service.setupScenarioJ());
    }

    @PostMapping("/scenario/k")
    public ResponseEntity<Map<String, Object>> scenarioK() {
        return ResponseEntity.ok(service.setupScenarioK());
    }

    @GetMapping("/observe")
    public ResponseEntity<Map<String, Object>> observe() {
        return ResponseEntity.ok(service.observe());
    }
}
