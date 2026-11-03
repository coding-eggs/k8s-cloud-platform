package com.coding.common.components.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * aud 白名单：平台自有客户端的 token 通过，别的客户端 / 无 aud 的 token 拒绝；白名单为空拒启。
 */
class PlatformJwtValidatorsTest {

    private final OAuth2TokenValidator<Jwt> validator =
            PlatformJwtValidators.withAudiences(List.of("platform-web-client"));

    private Jwt jwt(Object aud, Instant expiresAt) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "alice");
        if (aud != null) {
            claims.put("aud", aud);
        }
        return new Jwt("raw", expiresAt.minusSeconds(300), expiresAt, Map.of("alg", "RS256"), claims);
    }

    private Jwt jwt(Object aud) {
        return jwt(aud, Instant.now().plusSeconds(300));
    }

    @Test
    void platformClientAudiencePasses() {
        assertThat(validator.validate(jwt(List.of("platform-web-client"))).hasErrors()).isFalse();
    }

    @Test
    void anyMatchingAudienceInListPasses() {
        assertThat(validator.validate(jwt(List.of("other", "platform-web-client"))).hasErrors()).isFalse();
    }

    @Test
    void otherClientAudienceRejected() {
        assertThat(validator.validate(jwt(List.of("third-party-client"))).hasErrors()).isTrue();
    }

    @Test
    void missingAudienceRejected() {
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
    }

    @Test
    void defaultTimestampCheckStillApplies() {
        Jwt expired = jwt(List.of("platform-web-client"), Instant.now().minusSeconds(3600));
        assertThat(validator.validate(expired).hasErrors()).isTrue();
    }

    @Test
    void emptyAllowListRefusesToStart() {
        assertThatThrownBy(() -> PlatformJwtValidators.withAudiences(List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlatformJwtValidators.withAudiences(List.of(" ")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlatformJwtValidators.withAudiences(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
