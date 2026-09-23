package com.example.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the browser widget sends us.
 *
 * sessionId ties messages together so the backend can keep a short
 * conversation history per visitor (see ChatController's in-memory store).
 */
public record ChatRequest(
        @NotBlank(message = "sessionId is required")
        String sessionId,

        @NotBlank(message = "message must not be empty")
        @Size(max = 2000, message = "message is too long")
        String message
) {
}
