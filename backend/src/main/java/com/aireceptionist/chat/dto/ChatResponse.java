package com.aireceptionist.chat.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatResponse {

    private String reply;
    private String intent;
    private boolean leadCreated;
    private boolean appointmentCreated;
}
