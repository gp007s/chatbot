package com.example.chatbot.dto;

/**
 * One turn in a conversation. role is "system", "user", or "assistant".
 * This shape is intentionally provider-agnostic - Ollama's chat API happens
 * to use the exact same {role, content} JSON shape for messages, so this
 * record doubles as the request message type in OllamaApiDtos.
 */
public record ChatTurn(String role, String content) {
}
