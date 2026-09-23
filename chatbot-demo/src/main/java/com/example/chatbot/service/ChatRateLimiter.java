package com.example.chatbot.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how many messages one sessionId can send per minute.
 *
 * Every call to /api/chat costs you money at the model provider, so a
 * limiter like this (even a simple one) is worth having before you put a
 * chat link in front of real customers. For multi-instance deployments,
 * replace this with a shared limiter (e.g. Redis-backed) instead.
 */
@Component
public class ChatRateLimiter {

    private static final int MAX_MESSAGES_PER_WINDOW = 15;
    private static final long WINDOW_SECONDS = 60;

    private record Window(Instant startedAt, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String sessionId) {
        Instant now = Instant.now();
        Window window = windows.compute(sessionId, (id, existing) -> {
            if (existing == null || now.isAfter(existing.startedAt().plusSeconds(WINDOW_SECONDS))) {
                return new Window(now, new AtomicInteger(0));
            }
            return existing;
        });
        return window.count().incrementAndGet() <= MAX_MESSAGES_PER_WINDOW;
    }
}
