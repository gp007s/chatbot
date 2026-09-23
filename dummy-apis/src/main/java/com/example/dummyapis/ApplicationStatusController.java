package com.example.dummyapis;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Stands in for your real ApplicationStatusAPI. Returns one of a small set
 * of status CODES (not messages) - the chatbot-demo project maps codes to
 * customer-facing messages itself via application-status-messages.properties,
 * so this class deliberately never returns display text.
 *
 * Dummy rules:
 *  - a PCN containing "NOTFOUND" (case-insensitive) returns 404, simulating
 *    a PCN that doesn't match any application on file
 *  - any other PCN gets a status code picked deterministically from its
 *    hash, so the same PCN always returns the same status (useful for
 *    repeatable testing) without needing a real database behind this
 */
@RestController
public class ApplicationStatusController {

    private static final String[] CODES = {"APP100", "APP200", "APP300", "APP400", "APP500"};
    private static final String[] STAGES = {"Intake", "Underwriting", "Documentation Requested", "Decisioning", "Closed"};

    public record ApplicationStatusResponse(String pcn, String statusCode, String lastUpdated, String stage) {
    }

    @GetMapping("/api/applications/{pcn}")
    public ResponseEntity<ApplicationStatusResponse> getStatus(@PathVariable String pcn) {
        if (pcn.toUpperCase().contains("NOTFOUND")) {
            System.out.printf("[ApplicationStatusApi] pcn=%s -> 404 Not Found (dummy trigger)%n", pcn);
            return ResponseEntity.notFound().build();
        }

        int index = Math.floorMod(pcn.toUpperCase().hashCode(), CODES.length);
        String code = CODES[index];
        String stage = STAGES[index];

        System.out.printf("[ApplicationStatusApi] pcn=%s -> statusCode=%s stage=%s%n", pcn, code, stage);

        return ResponseEntity.ok(new ApplicationStatusResponse(pcn.toUpperCase(), code, LocalDate.now().toString(), stage));
    }
}
