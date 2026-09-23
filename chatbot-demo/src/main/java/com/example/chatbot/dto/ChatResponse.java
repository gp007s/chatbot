package com.example.chatbot.dto;

/**
 * What we send back to the browser widget.
 * sessionEnded tells the widget to disable input - used when verification
 * fails, or the session is otherwise closed server-side.
 */
public record ChatResponse(String reply, boolean sessionEnded) {

    public static ChatResponse ok(String reply) {
        return new ChatResponse(reply, false);
    }

    public static ChatResponse ended(String reply) {
        return new ChatResponse(reply, true);
    }
}
