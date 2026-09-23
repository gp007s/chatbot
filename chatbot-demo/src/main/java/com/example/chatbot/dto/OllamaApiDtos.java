package com.example.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Request/response shapes for POST http://localhost:11434/api/chat,
 * including Ollama's tool-calling (function-calling) fields.
 * Docs: https://docs.ollama.com/capabilities/tool-calling
 */
public class OllamaApiDtos {

    /** A single tool definition, in OpenAI/Ollama's function-calling shape. */
    public record ToolFunctionDef(String name, String description, Map<String, Object> parameters) {
    }

    public record ToolDef(String type, ToolFunctionDef function) {
        public static ToolDef function(ToolFunctionDef fn) {
            return new ToolDef("function", fn);
        }
    }

    /** What the model sends us when it wants to call a tool. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallFunction(String name, Map<String, Object> arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(ToolCallFunction function) {
    }

    /**
     * One chat message. Doubles as both an outgoing message (system/user/
     * assistant/tool) and the incoming response message - Ollama uses the
     * same shape both directions. Null fields are omitted when we send.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(String role, String content, List<ToolCall> tool_calls, String tool_name) {

        public static ChatMessage of(String role, String content) {
            return new ChatMessage(role, content, null, null);
        }

        /** The result of running a tool, sent back so the model can use it. */
        public static ChatMessage toolResult(String toolName, String resultJson) {
            return new ChatMessage("tool", resultJson, null, toolName);
        }
    }

    public record Options(Integer num_predict) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatRequest(
            String model,
            List<ChatMessage> messages,
            boolean stream,
            Options options,
            List<ToolDef> tools
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(String model, ChatMessage message, Boolean done) {
    }
}
