package com.coding.auth.client;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformClientSettingsTest {

    private static RegisteredClient client(ClientSettings settings) {
        return RegisteredClient.withId("id-1")
                .clientId("c1")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/cb")
                .clientSettings(settings)
                .build();
    }

    @Test
    void builtinFlagReadFromSettings() {
        ClientSettings flagged = ClientSettings.builder().setting(PlatformClientSettings.BUILTIN_KEY, true).build();
        assertThat(PlatformClientSettings.isBuiltin(client(flagged))).isTrue();
        assertThat(PlatformClientSettings.isBuiltin(client(ClientSettings.builder().build()))).isFalse();
    }

    @Test
    void enabledDefaultsTrue() {
        assertThat(PlatformClientSettings.isEnabled(client(ClientSettings.builder().build()))).isTrue();
    }

    @Test
    void withEnabledFalseIsReadBack() {
        ClientSettings base = ClientSettings.builder().requireProofKey(true).build();
        ClientSettings off = PlatformClientSettings.withEnabled(base, false).build();
        assertThat(PlatformClientSettings.isEnabled(client(off))).isFalse();
        assertThat(off.isRequireProofKey()).isTrue();
        ClientSettings on = PlatformClientSettings.withEnabled(off, true).build();
        assertThat(PlatformClientSettings.isEnabled(client(on))).isTrue();
    }

    @Test
    void catalogIsOpenidAndProfileOnly() {
        assertThat(PlatformScopes.CATALOG).extracting(OAuth2Scope::getName).containsExactly("openid", "profile");
        assertThat(PlatformScopes.contains("message.read")).isFalse();
        assertThat(PlatformScopes.describe("message.read").getDescription()).isEqualTo("message.read 权限");
    }
}
