package com.coding.auth.client;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * 停用 = 对授权服务器不存在（spec §4.11）。
 *
 * <p>SAS 的授权端点、token 端点（含 refresh_token 与 session-renewal）、客户端认证都经主仓库查客户端，
 * 在这里返回 null 就一处覆盖全部入口：新授权、换 token、续期都得到 invalid_client。
 * 已签出的 access token 自然到期（用户接受）。
 *
 * <p>管理侧必须绕过本装饰器（注入 {@code jdbcRegisteredClientRepository}），否则停用的行读不到、也就启用不回来。
 */
public class EnabledOnlyRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;

    public EnabledOnlyRegisteredClientRepository(RegisteredClientRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        delegate.save(registeredClient);
    }

    @Override
    public RegisteredClient findById(String id) {
        return visible(delegate.findById(id));
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return visible(delegate.findByClientId(clientId));
    }

    private static RegisteredClient visible(RegisteredClient client) {
        return client != null && PlatformClientSettings.isEnabled(client) ? client : null;
    }
}
