package com.aireceptionist.channel.repository;

import com.aireceptionist.channel.CommunicationChannel;
import com.aireceptionist.channel.entity.TenantChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantChannelConfigRepository extends JpaRepository<TenantChannelConfig, Long> {

    Optional<TenantChannelConfig> findByChannelAndExternalPhoneNumberId(
            CommunicationChannel channel,
            String externalPhoneNumberId
    );
}
