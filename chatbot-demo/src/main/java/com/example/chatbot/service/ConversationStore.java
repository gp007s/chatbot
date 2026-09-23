package com.example.chatbot.service;

import com.example.chatbot.dto.ChatTurn;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the last few turns of each visitor's conversation in memory, keyed
 * by sessionId, so the model has context across messages.
 *
 * This is a demo-grade store: it lives in one server's heap and is lost on
 * restart. For production, back this with Redis or a database keyed by
 * session, and add a TTL so old sessions get cleaned up.
 */
@Component
public class ConversationStore {

    private static final int MAX_TURNS_KEPT = 12; // ~6 back-and-forths

    private final Map<String, CopyOnWriteArrayList<ChatTurn>> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> pcnBySession = new ConcurrentHashMap<>();

    public List<ChatTurn> historyFor(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new CopyOnWriteArrayList<>());
    }

    public void append(String sessionId, ChatTurn turn) {
        CopyOnWriteArrayList<ChatTurn> turns = sessions.computeIfAbsent(sessionId, id -> new CopyOnWriteArrayList<>());
        turns.add(turn);
        while (turns.size() > MAX_TURNS_KEPT) {
            turns.remove(0);
        }
    }

    /** Remembers the PCN once seen in a session, so /end can use it even if not passed explicitly. */
    public void rememberPcn(String sessionId, String pcn) {
        pcnBySession.put(sessionId, pcn);
    }

    public String pcnFor(String sessionId) {
        return pcnBySession.get(sessionId);
    }

    /** Drops a session's history and remembered PCN, e.g. once it's been summarized and submitted. */
    public void clear(String sessionId) {
        sessions.remove(sessionId);
        pcnBySession.remove(sessionId);
    }
}
