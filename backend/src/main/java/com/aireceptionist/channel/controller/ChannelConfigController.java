package com.aireceptionist.channel.controller;

import com.aireceptionist.channel.dto.ChannelConfigResponse;
import com.aireceptionist.channel.dto.ChannelConfigUpsertRequest;
import com.aireceptionist.channel.dto.ChannelStatusUpdateRequest;
import com.aireceptionist.channel.entity.ChannelConfig.ReceptionChannel;
import com.aireceptionist.channel.service.ChannelConfigService;
import com.aireceptionist.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Channel Configuration", description = "Manage tenant-level channel configuration for AI receptionist")
public class ChannelConfigController {

    private final ChannelConfigService channelConfigService;

    @PostMapping("/tenants/{tenantId}/channels")
    @Operation(summary = "Create a channel configuration")
    public ResponseEntity<ApiResponse<ChannelConfigResponse>> create(
            @PathVariable Long tenantId,
            @Valid @RequestBody ChannelConfigUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(channelConfigService.create(tenantId, request)));
    }

    @GetMapping("/tenants/{tenantId}/channels")
    @Operation(summary = "List channel configurations for a tenant")
    public ResponseEntity<ApiResponse<List<ChannelConfigResponse>>> listByTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(channelConfigService.listByTenant(tenantId)));
    }

    @GetMapping("/tenants/{tenantId}/channels/{channel}")
    @Operation(summary = "Get channel configuration by channel type")
    public ResponseEntity<ApiResponse<ChannelConfigResponse>> getByChannel(
            @PathVariable Long tenantId,
            @PathVariable ReceptionChannel channel) {
        return ResponseEntity.ok(ApiResponse.ok(channelConfigService.findByTenantAndChannel(tenantId, channel)));
    }

    @PutMapping("/tenants/{tenantId}/channels/{channel}")
    @Operation(summary = "Update full channel configuration")
    public ResponseEntity<ApiResponse<ChannelConfigResponse>> update(
            @PathVariable Long tenantId,
            @PathVariable ReceptionChannel channel,
            @Valid @RequestBody ChannelConfigUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Channel config updated", channelConfigService.update(tenantId, channel, request)));
    }

    @PatchMapping("/tenants/{tenantId}/channels/{channel}/status")
    @Operation(summary = "Enable or disable a configured channel")
    public ResponseEntity<ApiResponse<ChannelConfigResponse>> updateStatus(
            @PathVariable Long tenantId,
            @PathVariable ReceptionChannel channel,
            @Valid @RequestBody ChannelStatusUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Channel status updated", channelConfigService.updateStatus(tenantId, channel, request)));
    }

    @DeleteMapping("/tenants/{tenantId}/channels/{channel}")
    @Operation(summary = "Delete channel configuration")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long tenantId,
            @PathVariable ReceptionChannel channel) {
        channelConfigService.delete(tenantId, channel);
        return ResponseEntity.ok(ApiResponse.ok("Channel config deleted", null));
    }
}
