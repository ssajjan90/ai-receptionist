package com.aireceptionist.chat.service;

import com.aireceptionist.chat.dto.ChatRequest;
import com.aireceptionist.chat.dto.ChatResponse;
import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.dto.InboundMessageRequest;
import com.aireceptionist.channel.dto.OutboundMessageResponse;
import com.aireceptionist.receptionist.AIReceptionistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AIReceptionistService aiReceptionistService;

    public ChatResponse processChat(ChatRequest request) {
        InboundMessageRequest inboundMessageRequest = new InboundMessageRequest();
        inboundMessageRequest.setTenantId(request.getTenantId());
        inboundMessageRequest.setCustomerPhone(request.getCustomerPhone());
        inboundMessageRequest.setMessage(request.getMessage());
        inboundMessageRequest.setChannel(CommunicationChannel.WEB_CHAT);

        OutboundMessageResponse outbound = aiReceptionistService.processInboundMessage(inboundMessageRequest);

        return ChatResponse.builder()
                .reply(outbound.getResponseMessage())
                .intent(null)
                .leadCreated(outbound.isLeadCreated())
                .appointmentCreated(false)
                .build();
    }
}
