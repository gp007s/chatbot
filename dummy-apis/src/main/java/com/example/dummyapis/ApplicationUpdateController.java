package com.example.dummyapis;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stands in for your real application-update API. Just logs what it
 * receives - swap in real persistence when you point chatbot-demo at your
 * actual backend.
 */
@RestController
public class ApplicationUpdateController {

    public record ApplicationUpdateRequest(String sessionId, String pcn, String summary, boolean resolved) {
    }

    @PostMapping("/api/application-updates")
    public ResponseEntity<Void> update(@RequestBody ApplicationUpdateRequest request) {
        System.out.printf("[ApplicationUpdateApi] session=%s pcn=%s resolved=%s%n",
                request.sessionId(), request.pcn(), request.resolved());
        System.out.println("  Summary: " + request.summary());
        return ResponseEntity.ok().build();
    }
}
