package com.example.chatbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls YOUR backend's application-update API once a conversation is
 * wrapped up.
 *
 * PLACEHOLDER CONTRACT - adjust submit() and ApplicationUpdateRequest to
 * match your real API. As written, this assumes:
 *   POST {base-url}  ->  body: { sessionId, pcn, summary, resolved }
 *
 * If your real endpoint expects a different shape or needs auth, change
 * only this class.
 */
@Component
public class ApplicationUpdateApiClient {

    private final RestClient restClient;

    public record ApplicationUpdateRequest(String sessionId, String pcn, String summary, boolean resolved) {
    }

    public ApplicationUpdateApiClient(@Value("${chatbot.tools.application-update-api.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                // .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    public void submit(ApplicationUpdateRequest request) {
        restClient.post()
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }
}
