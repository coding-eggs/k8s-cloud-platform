package com.coding.auth.client;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnabledOnlyRegisteredClientRepositoryTest {

    private final RegisteredClientRepository delegate = mock(RegisteredClientRepository.class);
    private final EnabledOnlyRegisteredClientRepository repo = new EnabledOnlyRegisteredClientRepository(delegate);

    private static RegisteredClient client(Boolean enabled) {
        ClientSettings.Builder s = ClientSettings.builder();
        if (enabled != null) s.setting(PlatformClientSettings.ENABLED_KEY, enabled);
        return RegisteredClient.withId("id-1").clientId("ext-1")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(s.build()).build();
    }

    @Test
    void disabledClientIsInvisibleById() {
        when(delegate.findById("id-1")).thenReturn(client(false));
        assertThat(repo.findById("id-1")).isNull();
    }

    @Test
    void disabledClientIsInvisibleByClientId() {
        when(delegate.findByClientId("ext-1")).thenReturn(client(false));
        assertThat(repo.findByClientId("ext-1")).isNull();
    }

    @Test
    void enabledAndUnflaggedClientsPassThrough() {
        RegisteredClient on = client(true);
        RegisteredClient legacy = client(null);
        when(delegate.findById("id-1")).thenReturn(on);
        when(delegate.findByClientId("ext-1")).thenReturn(legacy);
        assertThat(repo.findById("id-1")).isSameAs(on);
        assertThat(repo.findByClientId("ext-1")).isSameAs(legacy);
        assertThat(repo.findByClientId("missing")).isNull();
        repo.save(on);
        verify(delegate).save(on);
    }
}
