package com.aireceptionist.channel.dto;

import com.aireceptionist.channel.CommunicationChannel;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OutboundMessageResponse {

    private Long tenantId;

    private CommunicationChannel channel;

    private String customerPhone;

    private String responseMessage;

    private boolean leadCreated;

    private String conversationId;
}
