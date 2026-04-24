package com.aireceptionist.channel.service;

import com.aireceptionist.channel.dto.ChannelConfigResponse;
import com.aireceptionist.channel.dto.ChannelConfigUpsertRequest;
import com.aireceptionist.channel.dto.ChannelStatusUpdateRequest;
import com.aireceptionist.channel.entity.ChannelConfig;
import com.aireceptionist.channel.entity.ChannelConfig.ReceptionChannel;
import com.aireceptionist.channel.repository.ChannelConfigRepository;
import com.aireceptionist.common.exception.BadRequestException;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChannelConfigService {

    private final ChannelConfigRepository channelConfigRepository;
    private final TenantService tenantService;

    public List<ChannelConfigResponse> listByTenant(Long tenantId) {
        tenantService.getTenantOrThrow(tenantId);
        return channelConfigRepository.findByTenantId(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    public ChannelConfigResponse findByTenantAndChannel(Long tenantId, ReceptionChannel channel) {
        tenantService.getTenantOrThrow(tenantId);
        return toResponse(getOrThrow(tenantId, channel));
    }

    @Transactional
    public ChannelConfigResponse create(Long tenantId, ChannelConfigUpsertRequest request) {
        tenantService.getTenantOrThrow(tenantId);

        if (channelConfigRepository.existsByTenantIdAndChannel(tenantId, request.getChannel())) {
            throw new BadRequestException("Channel already configured for this tenant: " + request.getChannel());
        }

        ChannelConfig config = ChannelConfig.builder()
                .tenantId(tenantId)
                .channel(request.getChannel())
                .enabled(request.getEnabled())
                .botDisplayName(request.getBotDisplayName())
                .welcomeMessage(request.getWelcomeMessage())
                .webhookUrl(request.getWebhookUrl())
                .providerConfig(request.getProviderConfig())
                .build();

        return toResponse(channelConfigRepository.save(config));
    }

    @Transactional
    public ChannelConfigResponse update(Long tenantId, ReceptionChannel channel, ChannelConfigUpsertRequest request) {
        tenantService.getTenantOrThrow(tenantId);

        if (channel != request.getChannel()) {
            throw new BadRequestException("Path channel and payload channel must be same");
        }

        ChannelConfig existing = getOrThrow(tenantId, channel);
        existing.setEnabled(request.getEnabled());
        existing.setBotDisplayName(request.getBotDisplayName());
        existing.setWelcomeMessage(request.getWelcomeMessage());
        existing.setWebhookUrl(request.getWebhookUrl());
        existing.setProviderConfig(request.getProviderConfig());

        return toResponse(channelConfigRepository.save(existing));
    }

    @Transactional
    public ChannelConfigResponse updateStatus(Long tenantId, ReceptionChannel channel, ChannelStatusUpdateRequest request) {
        tenantService.getTenantOrThrow(tenantId);

        ChannelConfig existing = getOrThrow(tenantId, channel);
        existing.setEnabled(request.getEnabled());

        return toResponse(channelConfigRepository.save(existing));
    }

    @Transactional
    public void delete(Long tenantId, ReceptionChannel channel) {
        tenantService.getTenantOrThrow(tenantId);
        ChannelConfig existing = getOrThrow(tenantId, channel);
        channelConfigRepository.delete(existing);
    }

    private ChannelConfig getOrThrow(Long tenantId, ReceptionChannel channel) {
        return channelConfigRepository.findByTenantIdAndChannel(tenantId, channel)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Channel configuration for tenant " + tenantId + " and channel " + channel));
    }

    private ChannelConfigResponse toResponse(ChannelConfig config) {
        return ChannelConfigResponse.builder()
                .id(config.getId())
                .tenantId(config.getTenantId())
                .channel(config.getChannel())
                .enabled(config.getEnabled())
                .botDisplayName(config.getBotDisplayName())
                .welcomeMessage(config.getWelcomeMessage())
                .webhookUrl(config.getWebhookUrl())
                .providerConfig(config.getProviderConfig())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
