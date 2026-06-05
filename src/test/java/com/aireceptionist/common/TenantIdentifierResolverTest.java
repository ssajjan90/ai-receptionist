package com.aireceptionist.common;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.multitenancy.TenantIdentifierResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TenantIdentifierResolverTest {

    private final TenantIdentifierResolver resolver = new TenantIdentifierResolver();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void resolvesCurrentTenantFromContext() {
        TenantContext.setCurrentTenant("tenant-abc-123");
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo("tenant-abc-123");
    }

    @Test
    void returnsDefaultWhenNoTenantSet() {
        assertThat(resolver.resolveCurrentTenantIdentifier())
                .isEqualTo(TenantIdentifierResolver.DEFAULT_TENANT);
    }

    @Test
    void validateExistingCurrentSessionsIsTrue() {
        assertThat(resolver.validateExistingCurrentSessions()).isTrue();
    }
}
