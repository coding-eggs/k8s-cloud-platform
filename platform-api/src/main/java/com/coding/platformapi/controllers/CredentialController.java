package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.CredentialCreateRequest;
import com.coding.platformapi.models.CredentialIdRequest;
import com.coding.platformapi.models.CredentialTestRequest;
import com.coding.platformapi.models.CredentialTestResult;
import com.coding.platformapi.models.CredentialUpdateRequest;
import com.coding.platformapi.models.CredentialView;
import com.coding.platformapi.services.CredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 凭据管理（平台管理面，仅 admin）。本层只做 HTTP 绑定与 {@link ResponseData} 包装；
 * 业务在 {@link CredentialService}（分层约定见 docs/development/backend-layering.md）。
 *
 * <p>权限行见 V2026_10_13_3：{@code /credential/list} 与 {@code /credential/test} →
 * {@code platform:credential:read}；create / update / delete → {@code platform:credential:write}。
 * **五个端点都必须有行**，否则启动期交叉校验拒启。测试连接与列表**有意共用 read**
 * （能看即能测，spec §6.2）。
 *
 * <p><b>审计只标写操作与测试连接</b>：列表是纯读，不标。测试连接要标 ——
 * 它真的解密凭据并出站打对端，是本批唯一的「使用」路径（spec §7）。
 *
 * <p>{@code target} 对 create 取 name、其余取 id：请求体里只有 id。
 * **这与 {@code ClusterController} 完全一致**（cluster 的 update / delete / provision 同样是
 * {@code #request.clusterId}），是有意对齐，不是漏改。
 *
 * <p><b>没有单条详情端点</b>：列表返回的字段已足够，编辑表单的数据从列表行来。
 */
@Tag(name = "凭据管理", description = "平台级 git / registry 连接：CRUD + 测试连接（凭据内容永不回显）")
@RestController
@RequestMapping("/credential")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @Operation(summary = "凭据列表", description = "按 type、name 排序；**不含凭据内容**，只有是否已配置与指纹")
    @PostMapping("/list")
    public ResponseData<List<CredentialView>> list() {
        return new ResponseData<>(credentialService.list());
    }

    @Operation(summary = "测试连接",
            description = "解密凭据并真打一次对端：GIT 探 info/refs（需传 probeUrl），REGISTRY 探 /v2/。"
                    + "不改变业务状态，只写 last_verified_at / last_verify_error")
    @PostMapping("/test")
    @Auditable(action = AuditAction.CREDENTIAL_TEST, target = "#request.id")
    public ResponseData<CredentialTestResult> test(@RequestBody CredentialTestRequest request) {
        return new ResponseData<>(credentialService.test(request));
    }

    @Operation(summary = "新建凭据", description = "凭据明文落库即加密，此后永不回显")
    @PostMapping("/create")
    @Auditable(action = AuditAction.CREDENTIAL_CREATE, target = "#request.name")
    public ResponseData<CredentialView> create(@RequestBody CredentialCreateRequest request) {
        return new ResponseData<>(credentialService.create(request));
    }

    @Operation(summary = "更新凭据",
            description = "username / secret 留空 = 保持不变；authType 变更时必须重填")
    @PostMapping("/update")
    @Auditable(action = AuditAction.CREDENTIAL_UPDATE, target = "#request.id")
    public ResponseData<CredentialView> update(@RequestBody CredentialUpdateRequest request) {
        return new ResponseData<>(credentialService.update(request));
    }

    @Operation(summary = "删除凭据", description = "硬删除。被流水线引用时不做保护（不加外键是既定原则）")
    @PostMapping("/delete")
    @Auditable(action = AuditAction.CREDENTIAL_DELETE, target = "#request.id")
    public ResponseData<Void> delete(@RequestBody CredentialIdRequest request) {
        credentialService.delete(request.getId());
        return new ResponseData<>(null);
    }
}
