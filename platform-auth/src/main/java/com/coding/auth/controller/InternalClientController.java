package com.coding.auth.controller;

import com.coding.auth.client.ClientAdminRequest;
import com.coding.auth.client.ClientAdminView;
import com.coding.auth.client.ClientCreated;
import com.coding.auth.client.ClientSecrets;
import com.coding.auth.client.OAuth2Scope;
import com.coding.auth.service.ClientAdminService;
import com.coding.common.models.system.ResponseData;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户端管理的内部接口 —— 只给 platform-api 调（共享密钥见 {@code InternalTokenFilter}）。
 * 权限、审计都在 platform-api 的 {@code /oauth-clients/**}；这里只负责规则与读写，
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
    public ResponseData<ClientAdminView> get(@RequestBody IdRequest req) {
        return new ResponseData<>(clientAdminService.get(req.id()));
    }

    @PostMapping("/scopes")
    public ResponseData<List<OAuth2Scope>> scopes() {
        return new ResponseData<>(clientAdminService.scopes());
    }

    @PostMapping("/create")
    public ResponseData<ClientCreated> create(@RequestBody ClientAdminRequest req) {
        return new ResponseData<>(clientAdminService.create(req));
    }

    @PostMapping("/update")
    public ResponseData<ClientCreated> update(@RequestBody ClientAdminRequest req) {
        return new ResponseData<>(clientAdminService.update(req));
    }

    @PostMapping("/delete")
    public ResponseData<Void> delete(@RequestBody IdRequest req) {
        clientAdminService.delete(req.id());
        return new ResponseData<>();
    }

    @PostMapping("/enable")
    public ResponseData<Void> enable(@RequestBody EnableRequest req) {
        clientAdminService.setEnabled(req.id(), req.enabled());
        return new ResponseData<>();
    }

    @PostMapping("/reset-secret")
    public ResponseData<ClientSecrets> resetSecret(@RequestBody ResetSecretRequest req) {
        return new ResponseData<>(clientAdminService.resetSecret(req.id(), req.which()));
    }
}
