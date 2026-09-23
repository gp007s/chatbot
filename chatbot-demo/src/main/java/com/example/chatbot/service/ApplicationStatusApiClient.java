package com.example.chatbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls YOUR backend's application-status API.
 *
 * PLACEHOLDER CONTRACT - adjust getStatus() and ApplicationStatus to match
 * your real API. As written, this assumes:
 *   GET {base-url}/{pcn}  ->  200 OK, JSON body:
 *   { "pcn": "...", "statusCode": "...", "lastUpdated": "...", "stage": "..." }
 *
 * statusCode is a raw internal code (e.g. "APP200") - it never reaches the
 * customer directly. ApplicationStatusMessageMapper translates it to a
 * customer-facing message before anything gets said in the chat.
 *
 * If your real endpoint takes a different path, method, or auth header,
 * change only this class - ApplicationStatusTool, ChatController, and
 * everything else is unaffected.
 */
@Component
public class ApplicationStatusApiClient {

    private final RestClient restClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApplicationStatus(String pcn, String statusCode, String lastUpdated, String stage) {
    }

    public ApplicationStatusApiClient(@Value("${chatbot.tools.application-status-api.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                // If your real API needs auth, add it here, e.g.:
                // .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    public ApplicationStatus getStatus(String pcn) {
        return restClient.get()
                .uri("/{pcn}", pcn)
                .retrieve()
                .body(ApplicationStatus.class);
    }
}
