package com.example.chatbot.dto;

/** What we send back after ending a chat: the summary that was recorded. */
public record EndChatResponse(String summary) {
}
