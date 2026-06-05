package com.aireceptionist.whatsapp.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhatsAppWebhookPayload(
        String object,
        List<Entry> entry
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String id, List<Change> changes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(Value value, String field) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(
            @JsonProperty("messaging_product") String messagingProduct,
            Metadata metadata,
            List<Message> messages
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(@JsonProperty("phone_number_id") String phoneNumberId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String from, String type, Text text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Text(String body) {
    }
}
