package com.aireceptionist.channel.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChannelStatusUpdateRequest {

    @NotNull(message = "Enabled flag is required")
    private Boolean enabled;
}
