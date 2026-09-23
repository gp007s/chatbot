package com.example.chatbot.service;

import java.util.List;
import java.util.Map;

/**
 * A backend action the model can decide to call while answering a customer.
 *
 * Implement this and annotate the class @Component to register a new tool -
 * OllamaChatService picks up every ChatTool bean automatically and includes
 * it in the tool list sent to the model, so no other file needs to change.
 */
public interface ChatTool {

    /** Must be a stable, model-facing identifier, e.g. "get_case_status". */
    String name();

    /** Tells the model when and why to use this tool - be specific. */
    String description();

    /** JSON-schema "properties" for the tool's parameters, e.g. {"pcn": {"type":"string", ...}} */
    Map<String, Object> parameterProperties();

    /** Which of parameterProperties() the model must supply. */
    List<String> requiredParameters();

    /**
     * Runs the tool and returns a short JSON or plain-text result that gets
     * fed back to the model. Never throw here for expected failures (e.g.
     * case not found) - return a small JSON error object instead, so the
     * model can explain it to the customer.
     */
    String execute(Map<String, Object> arguments);
}
