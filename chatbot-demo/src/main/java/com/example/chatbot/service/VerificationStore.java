package com.example.chatbot.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks each session's progress through conversational identity
 * verification: the fields collected so far, a turn counter (used as a
 * safety cap - see chatbot.verification.max-turns), and whether the
 * session has passed, failed, or is still going.
 *
 * Demo-grade, in-memory, one JVM - see ConversationStore's docs for the
 * same production caveats (Redis/DB + TTL before real traffic).
 */
@Component
public class VerificationStore {

    public enum Status { IN_PROGRESS, VERIFIED, FAILED }

    public static class SessionVerification {
        public final Map<String, String> answers = new LinkedHashMap<>();
        public volatile Status status = Status.IN_PROGRESS;
        public volatile int turnCount = 0;
    }

    private final Map<String, SessionVerification> sessions = new ConcurrentHashMap<>();

    public SessionVerification stateFor(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new SessionVerification());
    }

    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }
}
