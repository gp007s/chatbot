package com.example.chatbot.dto;

import jakarta.validation.constraints.NotBlank;

/** What the widget sends once, when the chat panel first opens. */
public record StartChatRequest(
        @NotBlank(message = "sessionId is required")
        String sessionId
) {
}
