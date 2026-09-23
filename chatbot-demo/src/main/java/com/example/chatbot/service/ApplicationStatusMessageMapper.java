package com.example.chatbot.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Maps a raw ApplicationStatusAPI status code to the exact message the bot
 * is allowed to say, using application-status-messages.properties.
 *
 * This is the boundary that keeps internal status codes out of the
 * conversation entirely - callers only ever get back a customer-facing
 * sentence, never the code itself.
 */
@Component
public class ApplicationStatusMessageMapper {

    private static final String UNKNOWN_KEY = "UNKNOWN";
    private static final String FALLBACK_MESSAGE =
            "We could not determine your application's current status. Please contact support.";

    private final Properties messages = new Properties();

    public ApplicationStatusMessageMapper() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application-status-messages.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application-status-messages.properties not found on the classpath");
            }
            messages.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load application-status-messages.properties", e);
        }
    }

    public String messageFor(String statusCode) {
        if (statusCode == null || statusCode.isBlank()) {
            return unknownMessage();
        }
        return messages.getProperty(statusCode.toUpperCase(), unknownMessage());
    }

    private String unknownMessage() {
        return messages.getProperty(UNKNOWN_KEY, FALLBACK_MESSAGE);
    }
}
