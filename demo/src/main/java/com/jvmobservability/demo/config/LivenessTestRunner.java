package com.jvmobservability.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LivenessTestRunner — organic liveness failure trigger.
 *
 * This bean only activates when test.liveness.fail=true in application.properties.
 * When active, it throws during the ApplicationRunner phase of startup.
 *
 * Why this causes LivenessState = BROKEN organically:
 *
 *   Spring Boot startup order:
 *     1. All beans initialized
 *     2. Embedded web server starts  ← actuator is reachable from this point
 *     3. ApplicationRunner.run()     ← THIS bean throws here
 *            │
 *            ▼
 *     Spring catches the exception
 *            │
 *            ▼
 *     ApplicationFailedEvent published
 *            │
 *            ▼
 *     ApplicationAvailabilityBean listens for ApplicationFailedEvent
 *            │
 *            ▼
 *     LivenessState automatically set to BROKEN
 *            │
 *            ▼
 *     /actuator/health/liveness → HTTP 503
 *
 * No AvailabilityChangeEvent published manually.
 * No ProbeTestController involved.
 * Spring Boot does this entirely on its own.
 *
 * Test steps:
 *   1. Set test.liveness.fail=true in application.properties
 *   2. Restart the app
 *   3. GET /actuator/health/liveness → 503 DOWN  (organic)
 *   4. GET /actuator/health/readiness → 503 DOWN (Spring also sets REFUSING_TRAFFIC)
 *   5. Set test.liveness.fail=false, restart → both back to UP
 */
@Component
@ConditionalOnProperty(name = "test.liveness.fail", havingValue = "true")
public class LivenessTestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LivenessTestRunner.class);

    @Override
    public void run(ApplicationArguments args) {
        log.error("[LivenessTest] ApplicationRunner throwing — Spring will publish " +
                  "ApplicationFailedEvent → LivenessState = BROKEN automatically.");
        throw new RuntimeException(
                "Organic liveness failure: test.liveness.fail=true. " +
                "Check /actuator/health/liveness → HTTP 503. " +
                "Set test.liveness.fail=false and restart to recover.");
    }
}
