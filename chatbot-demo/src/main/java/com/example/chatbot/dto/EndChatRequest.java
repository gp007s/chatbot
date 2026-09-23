package com.example.chatbot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * What the widget sends when the customer ends the chat.
 * pcn is optional - if omitted, ChatController falls back to whatever PCN
 * was detected earlier in the conversation (see ConversationStore.pcnFor).
 */
public record EndChatRequest(
        @NotBlank(message = "sessionId is required")
        String sessionId,

        String pcn,

        boolean resolved
) {
}
