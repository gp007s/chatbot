package com.example.chatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds chatbot.verification.* from application.yml: whether the
 * verify-before-chat gate is on, the fixed opening line, the instruction
 * that drives the model's conversational collection of each field, a turn
 * cap, and which fields to collect (in no particular order - the model
 * gathers them naturally, not as a fixed script).
 *
 * Add or remove a field by editing application.yml only - no code change
 * needed. OllamaChatService.converseForVerification(...) builds a tool
 * from this list so the model can report whichever fields the customer
 * gives it, in whatever order they come.
 */
@Component
@ConfigurationProperties(prefix = "chatbot.verification")
public class VerificationProperties {

    private boolean enabled = true;
    private int maxTurns = 8;
    private String openingPrompt;
    private String systemPrompt;
    private List<FieldConfig> fields = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public String getOpeningPrompt() {
        return openingPrompt;
    }

    public void setOpeningPrompt(String openingPrompt) {
        this.openingPrompt = openingPrompt;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<FieldConfig> getFields() {
        return fields;
    }

    public void setFields(List<FieldConfig> fields) {
        this.fields = fields;
    }

    /** One field to collect: a stable key (sent to SecurityCheckApi) and a human-readable label. */
    public static class FieldConfig {
        private String key;
        private String label;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }
}
