package com.aireceptionist.channel.repository;

import com.aireceptionist.channel.entity.ChannelConfig;
import com.aireceptionist.channel.entity.ChannelConfig.ReceptionChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, Long> {

    List<ChannelConfig> findByTenantId(Long tenantId);

    Optional<ChannelConfig> findByTenantIdAndChannel(Long tenantId, ReceptionChannel channel);

    boolean existsByTenantIdAndChannel(Long tenantId, ReceptionChannel channel);
}
