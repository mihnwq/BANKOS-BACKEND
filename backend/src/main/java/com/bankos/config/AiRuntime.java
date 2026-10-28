package com.bankos.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared guard rails for every model call: offline/live mode, a wall-clock timeout and a
 * bounded tool-call budget. Model calls never run inside a JPA transaction.
 */
public class AiRuntime {

    private final BankosProperties.AiMode mode;
    private final Duration timeout;
    private final int maxToolCalls;
    private final ExecutorService executor;

    public AiRuntime(BankosProperties.AiMode mode, Duration timeout, int maxToolCalls, ExecutorService executor) {
        this.mode = mode;
        this.timeout = timeout;
        this.maxToolCalls = maxToolCalls;
        this.executor = executor;
    }

    /**
     * Builds a ChatClient when a chat model is configured; returns null otherwise (offline profile,
     * or live mode without a model, in which case every AI call fails closed and escalates to review).
     */
    public static ChatClient buildClient(ObjectProvider<ChatClient.Builder> builder) {
        try {
            ChatClient.Builder available = builder.getIfAvailable();
            return available == null ? null : available.build();
        } catch (BeansException e) {
            return null;
        }
    }

    public boolean isOffline() {
        return mode == BankosProperties.AiMode.OFFLINE;
    }

    public BankosProperties.AiMode mode() {
        return mode;
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }

    /** Runs a model call with the configured timeout; any failure surfaces as {@link AiUnavailableException}. */
    public <T> T callWithTimeout(Callable<T> call) {
        Future<T> future = executor.submit(call);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AiUnavailableException("AI call timed out after " + timeout.toSeconds() + "s", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("AI call interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiUnavailableException("AI service error: " + summarize(cause), cause);
        }
    }

    private static String summarize(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    public static class AiUnavailableException extends RuntimeException {

        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
