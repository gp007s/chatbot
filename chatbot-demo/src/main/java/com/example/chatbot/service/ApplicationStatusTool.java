package com.example.chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Lets the model look up a real application's status mid-conversation.
 *
 * This is the FALLBACK path: ChatController already tries to detect a PCN
 * deterministically with PcnExtractor and call the status API directly, as
 * soon as the customer provides one - so this tool mainly covers cases the
 * regex misses (e.g. the customer phrases it unusually) and the model
 * decides to ask for and use the tool itself.
 */
@Component
public class ApplicationStatusTool implements ChatTool {

    private final ApplicationStatusApiClient statusApiClient;
    private final ApplicationStatusMessageMapper statusMessageMapper;
    private final ObjectMapper objectMapper;

    /** What the model actually sees - the mapped message, never the raw code. */
    private record ToolResult(String pcn, String statusMessage, String lastUpdated) {
    }

    public ApplicationStatusTool(ApplicationStatusApiClient statusApiClient,
                                  ApplicationStatusMessageMapper statusMessageMapper,
                                  ObjectMapper objectMapper) {
        this.statusApiClient = statusApiClient;
        this.statusMessageMapper = statusMessageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "get_application_status";
    }

    @Override
    public String description() {
        return "Look up the current status of a customer's application using their PCN "
                + "(the primary reference number for their application). Use this whenever "
                + "the customer has given you a PCN and wants to know its status.";
    }

    @Override
    public Map<String, Object> parameterProperties() {
        return Map.of(
                "pcn", Map.of(
                        "type", "string",
                        "description", "The customer's PCN. Ask the customer for this if they haven't given it."
                )
        );
    }

    @Override
    public List<String> requiredParameters() {
        return List.of("pcn");
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object pcn = arguments.get("pcn");
        if (pcn == null || pcn.toString().isBlank()) {
            return "{\"error\": \"No PCN was provided. Ask the customer for their PCN.\"}";
        }
        try {
            ApplicationStatusApiClient.ApplicationStatus status = statusApiClient.getStatus(pcn.toString());
            String message = statusMessageMapper.messageFor(status.statusCode());
            return objectMapper.writeValueAsString(new ToolResult(status.pcn(), message, status.lastUpdated()));
        } catch (HttpClientErrorException.NotFound e) {
            return "{\"error\": \"No application was found with that PCN. Ask the customer to double-check it.\"}";
        } catch (RestClientException e) {
            // Backend down, timeout, etc. - hand the model a clean error it
            // can explain, instead of a stack trace.
            return "{\"error\": \"Could not retrieve status for that PCN right now.\"}";
        } catch (Exception e) {
            return "{\"error\": \"Unexpected error looking up application status.\"}";
        }
    }
}
