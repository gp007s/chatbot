package com.example.chatbot.service;

import com.example.chatbot.config.VerificationProperties;
import com.example.chatbot.dto.ChatTurn;
import com.example.chatbot.dto.OllamaApiDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

/**
 * Talks to a local Ollama server (https://ollama.com) for chat completions,
 * with three capabilities:
 *
 *  - ask(...)                   normal turn-by-turn chat, with tool
 *                                calling: the model may call any registered
 *                                ChatTool (e.g. a real status API) mid-
 *                                answer before replying to the customer.
 *  - converseForVerification(...) a separate tool-calling loop, scoped only
 *                                to identity verification: the model
 *                                collects fields conversationally via a
 *                                record_customer_info tool. It never decides
 *                                to call SecurityCheckApi itself - the
 *                                caller (ChatController) does that
 *                                deterministically once every field is
 *                                filled.
 *  - summarize(...)             a one-off call, no tools, that condenses a
 *                                finished conversation into a short summary
 *                                for your backend.
 *
 * To swap providers later, this is the only class (plus OllamaApiDtos) that
 * needs to change - ChatController, ConversationStore, the ChatTool
 * implementations, and the widget don't know or care which provider answers.
 */
@Service
public class OllamaChatService {

    private static final int MAX_TOOL_ROUNDS = 3; // guards against tool-call loops
    private static final String RECORD_INFO_TOOL = "record_customer_info";

    private final RestClient restClient;
    private final String model;
    private final Integer maxResponseTokens;
    private final String systemPrompt;
    private final Map<String, ChatTool> toolsByName;
    private final List<ToolDef> toolDefs;

    public OllamaChatService(
            @Value("${chatbot.provider.base-url}") String baseUrl,
            @Value("${chatbot.provider.model}") String model,
            @Value("${chatbot.provider.max-response-tokens}") int maxResponseTokens,
            @Value("${chatbot.provider.system-prompt}") String systemPrompt,
            List<ChatTool> tools // Spring injects every ChatTool bean here automatically
    ) {
        this.model = model;
        this.maxResponseTokens = maxResponseTokens;
        this.systemPrompt = systemPrompt;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("content-type", "application/json")
                .build();

        this.toolsByName = new HashMap<>();
        for (ChatTool tool : tools) {
            toolsByName.put(tool.name(), tool);
        }
        this.toolDefs = tools.stream()
                .map(t -> ToolDef.function(new ToolFunctionDef(
                        t.name(), t.description(),
                        Map.of(
                                "type", "object",
                                "properties", t.parameterProperties(),
                                "required", t.requiredParameters()
                        ))))
                .toList();
    }

    /**
     * Sends the running conversation (oldest first, last entry is the new
     * user message), letting the model call tools as needed, and returns
     * the final assistant reply text meant for the customer.
     */
    public String ask(List<ChatTurn> conversation) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.of("system", systemPrompt));
        for (ChatTurn turn : conversation) {
            messages.add(ChatMessage.of(turn.role(), turn.content()));
        }

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ChatResponse response = callOllama(messages, toolDefs);
            ChatMessage message = response.message();

            if (message == null) {
                return "Sorry, I didn't get a response. Please try again.";
            }
            if (message.tool_calls() == null || message.tool_calls().isEmpty()) {
                return message.content() != null ? message.content()
                        : "Sorry, I didn't get a response. Please try again.";
            }

            // The model wants to call one or more tools: run them and feed
            // the results back before asking for the next response.
            messages.add(message);
            for (ToolCall call : message.tool_calls()) {
                String toolName = call.function().name();
                ChatTool tool = toolsByName.get(toolName);
                String result = (tool != null)
                        ? tool.execute(call.function().arguments())
                        : "{\"error\": \"Unknown tool: " + toolName + "\"}";
                messages.add(ChatMessage.toolResult(toolName, result));
            }
        }

        return "Sorry, I wasn't able to finish looking that up. Please try again or ask for a human agent.";
    }

    /** What one verification turn produced: the model's reply, and the merged fields collected so far. */
    public record VerificationTurnResult(String reply, Map<String, String> updatedAnswers) {
    }

    /**
     * One turn of conversational identity-verification collection. The
     * model may call record_customer_info with whatever fields the
     * customer just gave it (partial is fine, any order, multiple at
     * once) - this method merges those into knownAnswers and returns the
     * result.
     *
     * IMPORTANT: this method never calls SecurityCheckApi and never tells
     * the customer they're verified or not - it only collects data. The
     * caller decides, in plain code, when every field is filled and only
     * then makes that real API call. If every field becomes filled during
     * this turn, reply() is null - the caller should skip showing any
     * model-authored text and proceed straight to its own deterministic
     * check, rather than spend a round-trip on a throwaway "let me check"
     * line the model might phrase misleadingly.
     */
    public VerificationTurnResult converseForVerification(
            List<ChatTurn> conversation,
            List<VerificationProperties.FieldConfig> fields,
            Map<String, String> knownAnswers,
            String verificationSystemPrompt
    ) {
        Map<String, String> answers = new LinkedHashMap<>(knownAnswers);
        ToolDef recordTool = buildRecordInfoTool(fields);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.of("system", verificationSystemPrompt + missingFieldsNote(fields, answers)));
        for (ChatTurn turn : conversation) {
            messages.add(ChatMessage.of(turn.role(), turn.content()));
        }

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ChatResponse response = callOllama(messages, List.of(recordTool));
            ChatMessage message = response.message();

            if (message == null) {
                return new VerificationTurnResult("Sorry, could you say that again?", answers);
            }
            if (message.tool_calls() == null || message.tool_calls().isEmpty()) {
                String reply = message.content() != null ? message.content() : "Could you tell me a bit more?";
                return new VerificationTurnResult(reply, answers);
            }

            messages.add(message);
            for (ToolCall call : message.tool_calls()) {
                if (RECORD_INFO_TOOL.equals(call.function().name())) {
                    mergeExtractedFields(fields, call.function().arguments(), answers);
                    messages.add(ChatMessage.toolResult(RECORD_INFO_TOOL, missingFieldsNote(fields, answers)));
                } else {
                    messages.add(ChatMessage.toolResult(call.function().name(), "{\"error\": \"Unknown tool\"}"));
                }
            }

            if (allFieldsFilled(fields, answers)) {
                // Complete: let the caller handle the deterministic check
                // and its own pre-written reply, instead of spending
                // another round asking the model for filler text.
                return new VerificationTurnResult(null, answers);
            }
        }

        return new VerificationTurnResult(
                "Sorry, I'm having trouble following that - could you tell me your full name, address, and date of birth?",
                answers);
    }

    private ToolDef buildRecordInfoTool(List<VerificationProperties.FieldConfig> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (VerificationProperties.FieldConfig field : fields) {
            properties.put(field.getKey(), Map.of(
                    "type", "string",
                    "description", "The customer's " + field.getLabel()
            ));
        }
        return ToolDef.function(new ToolFunctionDef(
                RECORD_INFO_TOOL,
                "Record any of the customer's identity details that they have given you, even partially "
                        + "or just one field at a time. Call this whenever they state their name, address, "
                        + "date of birth, or similar. Never guess, infer, or invent a value they did not give you.",
                Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of() // partial info per call is fine
                )
        ));
    }

    private void mergeExtractedFields(List<VerificationProperties.FieldConfig> fields,
                                       Map<String, Object> arguments,
                                       Map<String, String> answers) {
        if (arguments == null) {
            return;
        }
        for (VerificationProperties.FieldConfig field : fields) {
            Object value = arguments.get(field.getKey());
            if (value != null && !value.toString().isBlank()) {
                answers.put(field.getKey(), value.toString().trim());
            }
        }
    }

    private boolean allFieldsFilled(List<VerificationProperties.FieldConfig> fields, Map<String, String> answers) {
        return fields.stream().allMatch(f -> {
            String v = answers.get(f.getKey());
            return v != null && !v.isBlank();
        });
    }

    private String missingFieldsNote(List<VerificationProperties.FieldConfig> fields, Map<String, String> answers) {
        List<String> missing = fields.stream()
                .filter(f -> {
                    String v = answers.get(f.getKey());
                    return v == null || v.isBlank();
                })
                .map(VerificationProperties.FieldConfig::getLabel)
                .toList();
        return missing.isEmpty()
                ? " All required information has been provided."
                : " Still needed from the customer: " + String.join(", ", missing) + ".";
    }

    /**
     * Condenses a finished conversation into a short internal summary.
     * No tools involved - just the model reflecting on the transcript.
     */
    public String summarize(List<ChatTurn> conversation) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.of("system",
                "You are summarizing a customer support conversation for internal records. "
                        + "In 3-5 sentences, state: what the customer's issue was, what was found or "
                        + "discussed (including any case status that was looked up), and whether it "
                        + "appears resolved. Do not include greetings or pleasantries."));
        for (ChatTurn turn : conversation) {
            messages.add(ChatMessage.of(turn.role(), turn.content()));
        }
        messages.add(ChatMessage.of("user", "Provide the summary now."));

        ChatResponse response = callOllama(messages, List.of());
        String content = response.message() != null ? response.message().content() : null;
        return (content != null && !content.isBlank()) ? content.trim() : "No summary could be generated.";
    }

    private ChatResponse callOllama(List<ChatMessage> messages, List<ToolDef> tools) {
        ChatRequest request = new ChatRequest(
                model,
                messages,
                false, // stream=false: wait for the full reply instead of chunked tokens
                new Options(maxResponseTokens),
                tools.isEmpty() ? null : tools
        );

        try {
            ChatResponse response = restClient.post()
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null) {
                throw new ChatProviderException("Ollama returned an empty response.", null);
            }
            return response;

        } catch (ResourceAccessException e) {
            // Most common cause: Ollama isn't running, or the model isn't pulled yet.
            throw new ChatProviderException(
                    "Could not reach Ollama. Is it running? Try: ollama serve", e);
        } catch (RestClientResponseException e) {
            throw new ChatProviderException(
                    "Ollama returned an error, status " + e.getStatusCode(), e);
        }
    }

    /** Wraps provider failures so the controller can translate them to a clean HTTP response. */
    public static class ChatProviderException extends RuntimeException {
        public ChatProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
