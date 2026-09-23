package com.example.dummyapis;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stands in for your real SecurityCheckApi. No real identity checking here
 * - it's here so the chatbot-demo project's verification gate has
 * something to call while you build the real thing.
 *
 * Dummy rule: verification fails if the "address" field contains the word
 * "fail" (case-insensitive) - handy for testing the decline path in the
 * widget. Everything else passes.
 */
@RestController
public class SecurityCheckController {

    public record SecurityCheckRequest(String sessionId, Map<String, String> fields) {
    }

    public record SecurityCheckResponse(boolean verified, String reason) {
    }

    @PostMapping("/api/security-check")
    public SecurityCheckResponse check(@RequestBody SecurityCheckRequest request) {
        String address = (request.fields() != null) ? request.fields().getOrDefault("address", "") : "";
        boolean verified = !address.toLowerCase().contains("fail");
        String reason = verified ? null : "Address does not match records on file (dummy check).";

        System.out.printf("[SecurityCheckApi] session=%s fields=%s -> verified=%s%n",
                request.sessionId(), request.fields(), verified);

        return new SecurityCheckResponse(verified, reason);
    }
}
