package com.coding.auth.config;

import com.coding.auth.client.PlatformClientSettings;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenClaimsPolicyTest {

    private static RegisteredClient client(boolean builtin) {
        ClientSettings.Builder s = ClientSettings.builder();
        if (builtin) s.setting(PlatformClientSettings.BUILTIN_KEY, true);
        return RegisteredClient.withId("id").clientId("c")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(s.build()).build();
    }

    private static final User USER = new User("alice", "x", List.of());

    @Test
    void clientCredentialsPrincipalIsNoUser() {
        assertThat(TokenClaimsPolicy.classify(client(false), "ext-abc")).isEqualTo(TokenClaimsPolicy.Kind.NO_USER);
        assertThat(TokenClaimsPolicy.classify(client(true), null)).isEqualTo(TokenClaimsPolicy.Kind.NO_USER);
    }

    @Test
    void builtinClientWithUserIsPlatformUser() {
        assertThat(TokenClaimsPolicy.classify(client(true), USER)).isEqualTo(TokenClaimsPolicy.Kind.PLATFORM_USER);
    }

    @Test
    void externalClientWithUserIsExternalUser() {
        assertThat(TokenClaimsPolicy.classify(client(false), USER)).isEqualTo(TokenClaimsPolicy.Kind.EXTERNAL_USER);
    }

    @Test
    void externalNameFallsBackToUsername() {
        assertThat(TokenClaimsPolicy.externalDisplayName("Alice", "alice")).isEqualTo("Alice");
        assertThat(TokenClaimsPolicy.externalDisplayName(" ", "alice")).isEqualTo("alice");
        assertThat(TokenClaimsPolicy.externalDisplayName(null, "alice")).isEqualTo("alice");
    }
}
