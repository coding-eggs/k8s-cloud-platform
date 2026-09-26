package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.models.AllocationCreateRequest;
import com.coding.platformapi.models.AllocationDeleteRequest;
import com.coding.platformapi.models.AllocationQueryRequest;
import com.coding.platformapi.models.MemberAddRequest;
import com.coding.platformapi.models.MemberRemoveRequest;
import com.coding.platformapi.models.MemberRoleRequest;
import com.coding.platformapi.models.TenantCreateRequest;
import com.coding.platformapi.models.TenantKeyRequest;
import com.coding.platformapi.models.TenantMemberView;
import com.coding.platformapi.models.TenantUpdateRequest;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.security.TenantContextResolver;
import com.coding.platformapi.services.NamespaceAllocationService;
import com.coding.platformapi.services.TenantMemberService;
import com.coding.platformapi.services.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "租户管理", description = "租户 CRUD + 命名空间分配（给租户分配命名空间）+ 租户成员/角色管理")
@RestController
@RequestMapping("/tenant")
public class TenantController {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private NamespaceAllocationService allocationService;

    @Autowired
    private TenantMemberService memberSvc;

    @Autowired
    private TenantContextResolver ctx;

    @Autowired
    private AuthContext auth;

    @PostMapping("/create")
    @Operation(summary = "创建租户", description = "校验 serviceAccount 命名规范与唯一性，入库后在各启用集群创建 SA（tn-<serviceAccount>）")
    public ResponseData<PlatformTenant> create(@RequestBody TenantCreateRequest request) {
        return new ResponseData<>(tenantService.create(request));
    }

    @PostMapping("/list")
    @Operation(summary = "租户列表")
    public ResponseData<List<PlatformTenant>> list() {
        return new ResponseData<>(tenantService.list());
    }

    @PostMapping("/get")
    @Operation(summary = "租户详情")
    public ResponseData<PlatformTenant> get(@RequestBody TenantKeyRequest request) {
        return new ResponseData<>(tenantService.get(request));
    }

    @PostMapping("/update")
    @Operation(summary = "更新租户", description = "名称/状态；重新启用时补建各集群 SA（幂等）")
    public ResponseData<PlatformTenant> update(@RequestBody TenantUpdateRequest request) {
        return new ResponseData<>(tenantService.update(request));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除租户", description = "软删除 + 清理各集群 SA / RoleBinding + 清 client 缓存")
    public ResponseData<Void> delete(@RequestBody TenantKeyRequest request) {
        tenantService.delete(request);
        return new ResponseData<>();
    }

    @PostMapping("/provision")
    @Operation(summary = "重新开通租户 SA", description = "幂等重跑各启用集群的 SA 创建")
    public ResponseData<PlatformTenant> provision(@RequestBody TenantKeyRequest request) {
        return new ResponseData<>(tenantService.provision(request));
    }

    // ==================== 命名空间分配（给租户分配命名空间） ====================

    @PostMapping("/namespace/allocate")
    @Operation(summary = "分配命名空间", description = "先写分配记录（RoleBinding 端点边界前提），再执行 K8s 侧开通：ensure ns → sync 模板 ClusterRole → ensure SA → 建 RoleBinding，任一步失败回滚分配行；roleTemplateId 缺省用内置模板")
    public ResponseData<PlatformTenantNamespace> allocateNamespace(@RequestBody AllocationCreateRequest request) {
        return new ResponseData<>(allocationService.create(request));
    }

    @PostMapping("/namespace/list")
    @Operation(summary = "命名空间分配列表", description = "按租户/集群过滤，均可不传（查全部）")
    public ResponseData<List<PlatformTenantNamespace>> listNamespaces(@RequestBody(required = false) AllocationQueryRequest request) {
        if (request == null) {
            request = new AllocationQueryRequest();
        }
        return new ResponseData<>(allocationService.list(request));
    }

    @PostMapping("/namespace/deallocate")
    @Operation(summary = "取消命名空间分配", description = "删 RoleBinding + 物理删分配行；不删命名空间本身")
    public ResponseData<Void> deallocateNamespace(@RequestBody AllocationDeleteRequest request) {
        allocationService.delete(request);
        return new ResponseData<>();
    }

    // ==================== 租户成员/角色管理（自管/代管一致收口） ====================
    // 端点权限已由 seed 行 ANY-of（tenant:member:manage / platform:member:manage、tenant:overview:view）放行；
    // 生效租户统一由 TenantContextResolver 裁决：自管锁定 token 租户，代管必须显式传 tenantId。

    @PostMapping("/member/add")
    @Operation(summary = "加入租户成员", description = "已存在则幂等；不变量：授予角色前必须先是成员")
    public ResponseData<Void> memberAdd(@RequestBody MemberAddRequest request) {
        String tid = ctx.requireContext(tokenTenantId(), request.getTenantId());
        memberSvc.addMember(tid, request.getUserId());
        return new ResponseData<>();
    }

    @PostMapping("/member/remove")
    @Operation(summary = "移除租户成员", description = "级联删该成员在本租户的角色；不能移除最后一个 tenant-admin")
    public ResponseData<Void> memberRemove(@RequestBody MemberRemoveRequest request) {
        String tid = ctx.requireContext(tokenTenantId(), request.getTenantId());
        memberSvc.removeMember(tid, request.getUserId());
        return new ResponseData<>();
    }

    @PostMapping("/member/role/grant")
    @Operation(summary = "授予租户角色", description = "要求目标已是该租户成员（不变量 2）")
    public ResponseData<Void> memberRoleGrant(@RequestBody MemberRoleRequest request) {
        String tid = ctx.requireContext(tokenTenantId(), request.getTenantId());
        memberSvc.grantRole(tid, request.getUserId(), request.getRoleId());
        return new ResponseData<>();
    }

    @PostMapping("/member/role/revoke")
    @Operation(summary = "回收租户角色", description = "回收 tenant-admin 时校验租户至少保留一名管理员（不变量 3）")
    public ResponseData<Void> memberRoleRevoke(@RequestBody MemberRoleRequest request) {
        String tid = ctx.requireContext(tokenTenantId(), request.getTenantId());
        memberSvc.revokeRole(tid, request.getUserId(), request.getRoleId());
        return new ResponseData<>();
    }

    @PostMapping("/member/list")
    @Operation(summary = "租户成员列表", description = "含每个成员的角色 id 与 owner 标记（持有 tenant-admin）")
    public ResponseData<List<TenantMemberView>> memberList(@RequestBody MemberAddRequest request) {
        String tid = ctx.requireContext(tokenTenantId(), request.getTenantId());
        return new ResponseData<>(memberSvc.listMembers(tid));
    }

    /** token 内租户身份（自管用户）；admin/base token 无 tenantInfo → null（代管）。 */
    private String tokenTenantId() {
        var info = auth.current();
        return (info != null && info.getTenantInfo() != null) ? info.getTenantInfo().getTenantId() : null;
    }
}
