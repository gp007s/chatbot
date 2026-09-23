package com.example.chatbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a PCN (the primary key customers quote to identify their
 * application) out of a raw customer message, without needing the model
 * to recognize it.
 *
 * PLACEHOLDER PATTERN - the default below matches a generic 6-15 char
 * alphanumeric token (e.g. "PCN123456", "AB12CD34"). Replace
 * chatbot.pcn.pattern in application.yml with a regex that matches your
 * real PCN format (fixed length, a specific prefix, digits-only, etc.)
 * for reliable detection.
 */
@Component
public class PcnExtractor {

    private final Pattern pattern;

    public PcnExtractor(@Value("${chatbot.pcn.pattern}") String regex) {
        this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public Optional<String> extract(String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(message);
        return matcher.find() ? Optional.of(matcher.group().toUpperCase()) : Optional.empty();
    }
}
