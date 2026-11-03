package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.oauthclient.ClientAdminRequest;
import com.coding.platformapi.models.oauthclient.ClientAdminView;
import com.coding.platformapi.models.oauthclient.ClientCreated;
import com.coding.platformapi.models.oauthclient.ClientSecrets;
import com.coding.platformapi.models.oauthclient.EnableRequest;
import com.coding.platformapi.models.oauthclient.IdRequest;
import com.coding.platformapi.models.oauthclient.OAuthScope;
import com.coding.platformapi.models.oauthclient.ResetSecretRequest;
import com.coding.platformapi.outbound.AuthClientAdminClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OAuth 客户端管理（平台管理面，仅管理员）。规则与读写都在 platform-auth，本层只做权限、审计与转发。
 *
 * <p>权限行见 V2026_11_03_2：读 = {@code platform:oauth-client:view}，写 = {@code platform:oauth-client:manage}
 * （持有者能改内置客户端回调，等同 root）。**八个端点都必须有行**，否则启动期交叉校验拒启。
 *
 * <p>创建 / 重置 / 改 token 形态的响应体里有**只出现一次**的明文密钥；审计只记 target（id / clientId），
 * 不记响应体（{@code OAuthClientControllerTest} 钉住 target 的取值范围）。
 */
@Tag(name = "OAuth 客户端", description = "第三方登录客户端：内置客户端只能改回调与 token 有效期")
@RestController
@RequestMapping("/oauth-clients")
@RequiredArgsConstructor
public class OAuthClientController {

    private final AuthClientAdminClient authClient;

    @Operation(summary = "客户端列表", description = "不含任何密钥材料，只有 has*Secret 与 kid")
    @PostMapping("/list")
    public ResponseData<List<ClientAdminView>> list() {
        return new ResponseData<>(authClient.list());
    }

    @Operation(summary = "客户端详情")
    @PostMapping("/get")
    public ResponseData<ClientAdminView> get(@RequestBody IdRequest request) {
        return new ResponseData<>(authClient.get(request));
    }

    @Operation(summary = "scope 目录")
    @PostMapping("/scopes")
    public ResponseData<List<OAuthScope>> scopes() {
        return new ResponseData<>(authClient.scopes());
    }

    @Operation(summary = "新建客户端", description = "client_id 留空则生成 ext- 前缀；密钥由服务端生成，只在本次响应出现")
    @PostMapping("/create")
    @Auditable(action = AuditAction.OAUTH_CLIENT_CREATE, target = "#request.clientId")
    public ResponseData<ClientCreated> create(@RequestBody ClientAdminRequest request) {
        return new ResponseData<>(authClient.create(request));
    }

    @Operation(summary = "编辑客户端", description = "改成需要新对称密钥的形态时，响应里带新密钥（只此一次）")
    @PostMapping("/update")
    @Auditable(action = AuditAction.OAUTH_CLIENT_UPDATE, target = "#request.id")
    public ResponseData<ClientCreated> update(@RequestBody ClientAdminRequest request) {
        return new ResponseData<>(authClient.update(request));
    }

    @Operation(summary = "删除客户端", description = "内置客户端不可删除")
    @PostMapping("/delete")
    @Auditable(action = AuditAction.OAUTH_CLIENT_DELETE, target = "#request.id")
    public ResponseData<Void> delete(@RequestBody IdRequest request) {
        authClient.delete(request);
        return new ResponseData<>();
    }

    @Operation(summary = "启用 / 停用客户端", description = "停用后新授权与换 token 都会 invalid_client；内置客户端不可停用")
    @PostMapping("/enable")
    @Auditable(action = AuditAction.OAUTH_CLIENT_ENABLE, target = "#request.id")
    public ResponseData<Void> enable(@RequestBody EnableRequest request) {
        authClient.enable(request);
        return new ResponseData<>();
    }

    @Operation(summary = "重置密钥", description = "which = client | jws | jwe；新密钥只在本次响应出现")
    @PostMapping("/reset-secret")
    @Auditable(action = AuditAction.OAUTH_CLIENT_RESET_SECRET, target = "#request.id")
    public ResponseData<ClientSecrets> resetSecret(@RequestBody ResetSecretRequest request) {
        return new ResponseData<>(authClient.resetSecret(request));
    }
}
