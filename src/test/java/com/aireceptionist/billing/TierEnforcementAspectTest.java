package com.aireceptionist.billing;

import com.aireceptionist.billing.annotation.TierRequired;
import com.aireceptionist.billing.aspect.TierEnforcementAspect;
import com.aireceptionist.billing.domain.SubscriptionTier;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 7.1 (AC1, AC3): tier comes from the JWT-backed authentication, ranked BASIC < PRO < ENTERPRISE. */
class TierEnforcementAspectTest {

    public static class VoiceFeature {
        @TierRequired(SubscriptionTier.PRO)
        public String useVoice() {
            return "ok";
        }

        @TierRequired(SubscriptionTier.ENTERPRISE)
        public String useFranchise() {
            return "ok";
        }
    }

    private VoiceFeature proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new VoiceFeature());
        factory.setProxyTargetClass(true);
        factory.addAspect(new TierEnforcementAspect());
        return factory.getProxy();
    }

    private void loginAs(String tier) {
        SecurityContextHolder.getContext().setAuthentication(
                new TenantAwareAuthentication("t-1", "u-1", "OWNER", tier));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void basicTenantIsRejectedWithTierUpgradeRequired() {
        loginAs("BASIC");

        assertThatThrownBy(() -> proxied().useVoice())
                .isInstanceOf(AuthorizationException.class)
                .extracting("errorCode").isEqualTo("TIER_UPGRADE_REQUIRED");
    }

    @Test
    void proTenantPasses() {
        loginAs("PRO");
        assertThat(proxied().useVoice()).isEqualTo("ok");
    }

    @Test
    void enterpriseOutranksPro() {
        loginAs("ENTERPRISE");
        assertThat(proxied().useVoice()).isEqualTo("ok");
        assertThat(proxied().useFranchise()).isEqualTo("ok");
    }

    @Test
    void proIsBelowEnterprise() {
        loginAs("PRO");
        assertThatThrownBy(() -> proxied().useFranchise()).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void nonTenantCallerGetsForbiddenNotUpgradeMessage() {
        assertThatThrownBy(() -> proxied().useVoice())
                .isInstanceOf(AuthorizationException.class)
                .extracting("errorCode").isEqualTo("FORBIDDEN");
    }

    @Test
    void unknownTierIsTreatedAsBasic() {
        loginAs("GOLD");
        assertThatThrownBy(() -> proxied().useVoice()).isInstanceOf(AuthorizationException.class);
    }
}
