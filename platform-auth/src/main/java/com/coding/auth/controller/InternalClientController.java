package com.coding.auth.controller;

import com.coding.auth.client.ClientAdminRequest;
import com.coding.auth.client.ClientAdminView;
import com.coding.auth.client.ClientCreated;
import com.coding.auth.client.ClientSecrets;
import com.coding.auth.client.OAuth2Scope;
import com.coding.auth.service.ClientAdminService;
import com.coding.common.audit.AuditAction;
import com.coding.common.audit.Auditable;
import com.coding.common.models.system.ResponseData;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户端管理的内部接口，挂在平台网关后面：浏览器 {@code /api/oauth-clients/**} → 网关 → 这里（网关 spec §7）。
 * 接口级权限由网关按权限表判（service=platform-auth 的行），本服务只认 JWT（见 {@code InternalApiSecurityConfig}）；
 * 写操作审计在这里记（action / target 与原 api 转发层一致）。规则与读写都在 auth，
 * 因为对称密钥要用 auth 自己的加密密钥落库，私钥不出 auth（spec §4.6）。
 */
@RestController
@RequestMapping("/internal/clients")
@RequiredArgsConstructor
public class InternalClientController {

    private final ClientAdminService clientAdminService;

    public record IdRequest(String id) {
    }

    public record EnableRequest(String id, boolean enabled) {
    }

    public record ResetSecretRequest(String id, String which) {
    }

    @PostMapping("/list")
    public ResponseData<List<ClientAdminView>> list() {
        return new ResponseData<>(clientAdminService.list());
    }

    @PostMapping("/get")
    public ResponseData<ClientAdminView> get(@RequestBody IdRequest request) {
        return new ResponseData<>(clientAdminService.get(request.id()));
    }

    @PostMapping("/scopes")
    public ResponseData<List<OAuth2Scope>> scopes() {
        return new ResponseData<>(clientAdminService.scopes());
    }

    @PostMapping("/create")
    @Auditable(action = AuditAction.OAUTH_CLIENT_CREATE, target = "#request.clientId")
    public ResponseData<ClientCreated> create(@RequestBody ClientAdminRequest request) {
        return new ResponseData<>(clientAdminService.create(request));
    }

    @PostMapping("/update")
    @Auditable(action = AuditAction.OAUTH_CLIENT_UPDATE, target = "#request.id")
    public ResponseData<ClientCreated> update(@RequestBody ClientAdminRequest request) {
        return new ResponseData<>(clientAdminService.update(request));
    }

    @PostMapping("/delete")
    @Auditable(action = AuditAction.OAUTH_CLIENT_DELETE, target = "#request.id")
    public ResponseData<Void> delete(@RequestBody IdRequest request) {
        clientAdminService.delete(request.id());
        return new ResponseData<>();
    }

    @PostMapping("/enable")
    @Auditable(action = AuditAction.OAUTH_CLIENT_ENABLE, target = "#request.id")
    public ResponseData<Void> enable(@RequestBody EnableRequest request) {
        clientAdminService.setEnabled(request.id(), request.enabled());
        return new ResponseData<>();
    }

    @PostMapping("/reset-secret")
    @Auditable(action = AuditAction.OAUTH_CLIENT_RESET_SECRET, target = "#request.id")
    public ResponseData<ClientSecrets> resetSecret(@RequestBody ResetSecretRequest request) {
        return new ResponseData<>(clientAdminService.resetSecret(request.id(), request.which()));
    }
}
