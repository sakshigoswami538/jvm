package com.jvmobservability.demo.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AsyncConfig — configures the bounded thread pool with CallerRunsPolicy.
 *
 * PROBLEM (observed in Prometheus metrics):
 *   executor_pool_core_threads  = 10
 *   executor_pool_max_threads   = 30
 *   executor_queue_remaining_tasks = 100
 *
 *   No RejectedExecutionHandler set → default AbortPolicy.
 *   When 30 threads busy + 100 queue full → RejectedExecutionException → 500 error.
 *   Upload request dropped silently.
 *
 * FIX — CallerRunsPolicy:
 *   When pool+queue is full, the calling HTTP thread runs the task itself.
 *   No exception. No dropped request.
 *   Caller naturally slows down = built-in backpressure.
 *
 * NEW METRIC:
 *   executor_rejected_tasks_total{pool="applicationTaskExecutor"}
 *   Counter increments each time CallerRunsPolicy kicks in.
 *   rate(executor_rejected_tasks_total[1m]) > 0 → Grafana alert: overload.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor(MeterRegistry meterRegistry) {

        Counter rejectedCounter = Counter.builder("executor.rejected.tasks")
                .description("Tasks redirected to caller thread when pool+queue are full (CallerRunsPolicy)")
                .tag("pool", "taskExecutor")
                .register(meterRegistry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("music-task-");

        // CallerRunsPolicy: instead of throwing RejectedExecutionException,
        // run the task on the caller's thread. Also increments the counter so
        // Grafana can alert when this starts happening under load.
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            rejectedCounter.increment();
            new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(runnable, pool);
        });

        executor.initialize();
        return executor;
    }
}
