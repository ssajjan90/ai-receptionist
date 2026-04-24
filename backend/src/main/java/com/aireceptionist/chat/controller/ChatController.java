package com.aireceptionist.chat.controller;

import com.aireceptionist.chat.dto.ChatRequest;
import com.aireceptionist.chat.dto.ChatResponse;
import com.aireceptionist.chat.service.ChatService;
import com.aireceptionist.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "AI receptionist chat endpoint")
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/chat")
    @Operation(
            summary = "Send a message to the AI receptionist",
            description = "Processes a customer message via AIReceptionistService and returns the AI reply."
    )
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = chatService.processChat(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
