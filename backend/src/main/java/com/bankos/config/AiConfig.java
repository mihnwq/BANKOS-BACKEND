package com.bankos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AiConfig {

    /** Threads for model calls, so a slow model can be cut off by a timeout. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService aiExecutor() {
        return Executors.newFixedThreadPool(4, named("bankos-ai-"));
    }

    /** One batch pipeline at a time; processing runs in the background when bankos.processing.async=true. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService processingExecutor() {
        return Executors.newSingleThreadExecutor(named("bankos-batch-"));
    }

    @Bean
    public AiRuntime aiRuntime(BankosProperties properties, ExecutorService aiExecutor) {
        BankosProperties.Ai ai = properties.ai();
        return new AiRuntime(ai.mode(), Duration.ofSeconds(ai.timeoutSeconds()), ai.maxToolCalls(), aiExecutor);
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
