package com.jvmobservability.demo.controller;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ProbeTestController — exists ONLY because LivenessState cannot be changed
 * from outside the JVM. No curl command, docker stop, or actuator call can
 * publish AvailabilityChangeEvent<LivenessState> into a running process.
 *
 * Readiness scenarios are covered organically (no controller needed):
 *   refuse traffic → docker stop <mongo>   → MongoHealthIndicator DOWN → readiness 503
 *   accept traffic → docker start <mongo>  → MongoHealthIndicator UP   → readiness 200
 *
 * Liveness scenarios require this controller (no organic alternative post-startup):
 *   break  → POST /api/probe/liveness/break  → publishes LivenessState.BROKEN
 *   heal   → POST /api/probe/liveness/heal   → publishes LivenessState.CORRECT
 *
 * Full test sequence:
 *   Step 1: GET  /actuator/health                 → baseline (both UP)
 *   Step 2: POST /api/probe/liveness/break        → flip liveness BROKEN
 *   Step 3: GET  /actuator/health/liveness        → observe 503
 *   Step 4: GET  /actuator/health/readiness       → still 200 (independent)
 *   Step 5: POST /api/probe/liveness/heal         → restore liveness CORRECT
 *   Step 6: docker stop <mongo>                   → flip readiness DOWN organically
 *   Step 7: GET  /actuator/health/readiness       → observe 503
 *   Step 8: GET  /actuator/health/liveness        → still 200 (independent)
 *   Step 9: docker start <mongo>                  → restore readiness UP organically
 */
@RestController
@RequestMapping("/api/probe")
public class ProbeTestController {

    private final ApplicationAvailability   availability;
    private final ApplicationEventPublisher eventPublisher;

    public ProbeTestController(ApplicationAvailability availability,
                               ApplicationEventPublisher eventPublisher) {
        this.availability   = availability;
        this.eventPublisher = eventPublisher;
    }

    // POST /api/probe/liveness/break
    // Simulates an unrecoverable JVM-level fault (deadlock, data corruption, OOM aftermath).
    // Kubernetes effect: pod is KILLED and restarted.
    // Readiness is unaffected — the two states are completely independent.
    @PostMapping("/liveness/break")
    public ResponseEntity<Map<String, Object>> breakLiveness() {
        AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("liveness_state",        "BROKEN");
        result.put("verify",                "GET /actuator/health/liveness → HTTP 503");
        result.put("readiness_unaffected",  "GET /actuator/health/readiness → still HTTP 200");
        result.put("kubernetes_effect",     "pod would be KILLED and restarted");
        result.put("restore",               "POST /api/probe/liveness/heal");
        return ResponseEntity.ok(result);
    }

    // POST /api/probe/liveness/heal
    @PostMapping("/liveness/heal")
    public ResponseEntity<Map<String, Object>> healLiveness() {
        AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.CORRECT);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("liveness_state", "CORRECT");
        result.put("verify",         "GET /actuator/health/liveness → HTTP 200");
        result.put("current_readiness", availability.getReadinessState().name());
        return ResponseEntity.ok(result);
    }
}
