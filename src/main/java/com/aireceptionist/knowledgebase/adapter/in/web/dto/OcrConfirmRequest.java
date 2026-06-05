package com.aireceptionist.knowledgebase.adapter.in.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record OcrConfirmRequest(
        @NotEmpty List<@Valid OcrProductEntry> entries
) {
}
