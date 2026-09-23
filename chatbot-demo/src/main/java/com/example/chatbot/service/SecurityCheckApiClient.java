package com.example.chatbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Calls YOUR backend's SecurityCheckApi once all verification fields have
 * been collected from the customer.
 *
 * PLACEHOLDER CONTRACT - adjust verify() and SecurityCheckResult to match
 * your real API. As written, this assumes:
 *   POST {base-url}  ->  body: { sessionId, fields: { key: value, ... } }
 *   200 OK, JSON body: { "verified": true|false, "reason": "..." }
 *
 * fields' keys come straight from chatbot.verification.fields in
 * application.yml (e.g. "fullName", "address"), so no code change is
 * needed there if you add/remove a field - only here if your real API
 * expects a different envelope shape.
 */
@Component
public class SecurityCheckApiClient {

    private final RestClient restClient;

    public record SecurityCheckRequest(String sessionId, Map<String, String> fields) {
    }

    public record SecurityCheckResult(boolean verified, String reason) {
    }

    public SecurityCheckApiClient(@Value("${chatbot.tools.security-check-api.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                // .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    public SecurityCheckResult verify(String sessionId, Map<String, String> fields) {
        return restClient.post()
                .body(new SecurityCheckRequest(sessionId, fields))
                .retrieve()
                .body(SecurityCheckResult.class);
    }
}
