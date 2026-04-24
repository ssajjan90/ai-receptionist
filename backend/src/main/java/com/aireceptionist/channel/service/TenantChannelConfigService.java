package com.aireceptionist.channel.service;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.entity.TenantChannelConfig;
import com.aireceptionist.channel.repository.TenantChannelConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantChannelConfigService {

    private final TenantChannelConfigRepository tenantChannelConfigRepository;

    public Optional<TenantChannelConfig> findByChannelAndExternalPhoneNumberId(
            CommunicationChannel channel,
            String externalPhoneNumberId
    ) {
        return tenantChannelConfigRepository.findByChannelAndExternalPhoneNumberId(channel, externalPhoneNumberId)
                .filter(config -> Boolean.TRUE.equals(config.getEnabled()));
    }
}
