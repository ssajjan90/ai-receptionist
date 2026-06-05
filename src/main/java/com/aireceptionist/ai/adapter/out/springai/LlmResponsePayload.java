package com.aireceptionist.ai.adapter.out.springai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmResponsePayload(String response, double confidence, String language) {
}
