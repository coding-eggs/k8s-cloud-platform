package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.common.models.k8s.dto.admin.NamespaceRecoveryPreview;
import com.coding.common.security.AuthContext;
import com.coding.common.utils.K8sNaming;
import com.coding.data.mapper.auth.*;
import com.coding.data.models.auth.NamespaceRequest;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.k8s.K8sLifecycleClient;
import com.coding.platformapi.models.AllocationCreateRequest;
import com.coding.platformapi.models.NamespaceQuotaUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NamespaceRequestService {
    private static final Set<String> QUOTAS = Set.of("requestsCpu","limitsCpu","requestsMemory","limitsMemory","pods","persistentVolumeClaims");
    private final AuthContext auth;
    private final NamespaceRequestMapper requests;
    private final PlatformTenantNamespaceMapper allocations;
    private final NamespaceAllocationService allocationService;
    private final NamespaceService namespaces;
    private final K8sLifecycleClient lifecycle;
    private final TransactionTemplate transaction;

    public record Review(String id, boolean approve, String clusterId, String reason, BigDecimal approvedValue) {}
    public record Recovery(String allocationId, String uid, String confirmation) {}

    public void submit(NamespaceRequest r) {
        String tenant = auth.hatTenantId();
        if (tenant == null || r.getId() == null || !r.getId().matches("[a-zA-Z0-9-]{16,64}")
                || r.getPurpose() == null || r.getPurpose().isBlank() || r.getPurpose().length() > 1000)
            throw new IllegalArgumentException("需要租户身份、提交标识及申请原因");
        r.setTenantId(tenant);
        r.setApplicant(auth.current().getUsername());
        r.setId(java.util.UUID.nameUUIDFromBytes((tenant + "|" + r.getApplicant() + "|" + r.getId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        if (r.getKind() == null || !Set.of("OPEN","EXPAND","RENEW").contains(r.getKind())) throw new IllegalArgumentException("申请类型无效");
        if ("OPEN".equals(r.getKind())) {
            K8sNaming.validateRawName(r.getNamespace(), "", "namespace");
            r.setClusterId(null);
            r.setAllocationId(null);
        } else {
            var allocation = allocations.selectByPrimaryKey(r.getAllocationId());
            if (allocation == null || !tenant.equals(allocation.getTenantId())) throw new IllegalArgumentException("空间分配不存在");
            r.setNamespace(allocation.getNamespace());
            r.setClusterId(allocation.getClusterId());
            if ("RENEW".equals(r.getKind()) && (allocation.getExpiresAt() == null || r.getExpiresAt() == null ||
                    r.getExpiresAt() <= allocation.getExpiresAt())) throw new IllegalArgumentException("请选择更晚的续期日期");
            if ("EXPAND".equals(r.getKind()) && (!"ACTIVE".equals(allocation.getLifecycle()) || expired(allocation)))
                throw new IllegalArgumentException("分配已到期或正在回收");
        }
        if (r.getExpiresAt() != null && r.getExpiresAt() <= System.currentTimeMillis()) throw new IllegalArgumentException("申请期限已过");
        if (r.getQuotaKey() != null || "EXPAND".equals(r.getKind())) {
            if (r.getQuotaKey() == null || !QUOTAS.contains(r.getQuotaKey()) || r.getTargetValue() == null || r.getTargetValue().signum() <= 0
                    || ("EXPAND".equals(r.getKind()) && (r.getBaseline() == null || r.getTargetValue().compareTo(r.getBaseline()) <= 0)))
                throw new IllegalArgumentException("请填写当前上限及更高的目标总量");
        }
        try { requests.insert(r); }
        catch (DuplicateKeyException e) {
            var existing = requests.lock(r.getId());
            if (existing == null || !tenant.equals(existing.getTenantId()) || !r.getApplicant().equals(existing.getApplicant())) throw e;
        }
    }

    public List<NamespaceRequest> mine() {
        return requests.mine(auth.hatTenantId(), auth.current().getUsername());
    }

    @Transactional
    public List<PlatformTenantNamespace> reminders() {
        String tenant = auth.hatTenantId();
        if (tenant == null) requireAdmin();
        String recipient = auth.current().getUsername();
        long now = System.currentTimeMillis();
        return requests.soon(tenant).stream().filter(row -> requests.deliver(row.getId(),row.getExpiresAt(),
                row.getExpiresAt() - now <= 86_400_000L ? 1 : 7, recipient) == 1).toList();
    }

    public List<NamespaceRequest> list(String tenantId) {
        requireAdmin();
        return requests.list(tenantId);
    }

    public void review(Review decision) {
        requireAdmin();
        // 先固定开通目标。K8s 开通必须在外层事务之外提交分配记录，崩溃重试也必须命中同一空间。
        transaction.executeWithoutResult(tx -> {
            var request = requests.lock(decision.id());
            if (request == null) throw new IllegalArgumentException("申请不存在");
            if ("OPEN".equals(request.getKind()) && "PENDING".equals(request.getStatus()) && decision.approve()) {
                if (decision.clusterId() == null || decision.clusterId().isBlank()) throw new IllegalArgumentException("请选择集群");
                request.setClusterId(decision.clusterId());
                request.setApprovedValue(decision.approvedValue() == null ? request.getTargetValue() : decision.approvedValue());
                if (request.getQuotaKey() != null) validateAmount(request);
                if (request.getExpiresAt() != null && request.getExpiresAt() <= System.currentTimeMillis())
                    throw new IllegalArgumentException("申请截止时间已过");
                request.setStatus("PROVISIONING");
                request.setReviewer(auth.current().getUsername());
                requests.reviewed(request);
            }
        });
        try {
            transaction.executeWithoutResult(tx -> applyDecision(decision));
        } catch (RuntimeException e) {
            transaction.executeWithoutResult(tx -> {
                var request = requests.lock(decision.id());
                if ("PROVISIONING".equals(request.getStatus())) {
                    if (allocations.selectByPrimaryKey(request.getId()) == null) request.setStatus("PENDING");
                    String message = Objects.toString(e.getMessage(), "开通执行失败");
                    request.setReason(message.substring(0, Math.min(990,message.length())));
                    requests.reviewed(request);
                }
            });
            throw e;
        }
    }

    private void applyDecision(Review decision) {
        var r = requests.lock(decision.id());
        if (r == null) throw new IllegalArgumentException("申请不存在");
        if (!Set.of("PENDING","PROVISIONING").contains(r.getStatus())) return;
        if ("PROVISIONING".equals(r.getStatus()) && (!decision.approve() || !Objects.equals(r.getClusterId(), decision.clusterId())))
            throw new IllegalArgumentException("空间正在开通，请在原集群继续执行");
        r.setReviewer(auth.current().getUsername());
        r.setReason(decision.reason());
        if (!decision.approve()) {
            if (decision.reason() == null || decision.reason().isBlank()) throw new IllegalArgumentException("请填写驳回原因");
            r.setStatus("REJECTED");
        } else {
            PlatformTenantNamespace row;
            if ("OPEN".equals(r.getKind())) {
                if (decision.clusterId() == null || decision.clusterId().isBlank()) throw new IllegalArgumentException("请选择目标集群");
                var create = new AllocationCreateRequest();
                create.setTenantId(r.getTenantId());
                create.setNamespace(r.getNamespace());
                create.setClusterId(r.getClusterId());
                create.setExpiresAt(r.getExpiresAt());
                row = allocationService.createForRequest(create, r.getId());
                r.setClusterId(row.getClusterId());
                r.setAllocationId(row.getId());
            } else {
                row = allocations.lock(r.getAllocationId());
                if (row == null || !Objects.equals(row.getTenantId(), r.getTenantId())) throw new IllegalArgumentException("原空间分配已失效");
            }
            if ("RENEW".equals(r.getKind())) {
                if (r.getExpiresAt() <= System.currentTimeMillis() || row.getExpiresAt() == null || r.getExpiresAt() <= row.getExpiresAt()
                        || !Set.of("ACTIVE","EXPIRED","STOPPED").contains(row.getLifecycle()))
                    throw new IllegalArgumentException("续期目标已变化或正在回收");
                allocations.renew(row.getId(), r.getExpiresAt(), "ACTIVE".equals(row.getLifecycle()) && !expired(row) ? "ACTIVE" : "RESTORING");
            } else if (r.getQuotaKey() != null) {
                if (expired(row) || !"ACTIVE".equals(Objects.requireNonNullElse(row.getLifecycle(), "ACTIVE"))) throw new IllegalArgumentException("分配已到期");
                if (!"OPEN".equals(r.getKind())) r.setApprovedValue(decision.approvedValue() == null ? r.getTargetValue() : decision.approvedValue());
                applyQuota(r);
            }
            r.setStatus("APPROVED");
        }
        requests.reviewed(r);
    }

    private static void validateAmount(NamespaceRequest r) {
        BigDecimal value = r.getApprovedValue();
        if (value.signum() <= 0 || value.compareTo(r.getTargetValue()) > 0 ||
                (r.getBaseline() != null && value.compareTo(r.getBaseline()) <= 0)) throw new IllegalArgumentException("批准总量超出申请范围");
        if (Set.of("pods","persistentVolumeClaims").contains(r.getQuotaKey())) value.intValueExact();
    }

    private void applyQuota(NamespaceRequest r) {
        validateAmount(r);
        BigDecimal value = r.getApprovedValue();
        ResourceQuotaDTO quota = namespaces.quotaGet(r.getClusterId(), r.getNamespace());
        if (quota == null) quota = new ResourceQuotaDTO();
        if (Boolean.TRUE.equals(quota.getMultiple())) throw new IllegalArgumentException("空间有多份配额，请核对其他约束");
        var fields = new BeanWrapperImpl(quota);
        Object old = fields.getPropertyValue(r.getQuotaKey());
        if (old != null && new BigDecimal(old.toString()).compareTo(value) == 0) return;
        if (old == null ? r.getBaseline() != null : r.getBaseline() == null || new BigDecimal(old.toString()).compareTo(r.getBaseline()) != 0)
            throw new IllegalArgumentException("当前上限已变化，请重新核对");
        fields.setPropertyValue(r.getQuotaKey(), value);
        var update = new NamespaceQuotaUpsertRequest();
        update.setClusterId(r.getClusterId());
        update.setNamespace(r.getNamespace());
        update.setQuota(quota);
        namespaces.quotaUpsert(update);
        Object actual = new BeanWrapperImpl(namespaces.quotaGet(r.getClusterId(), r.getNamespace())).getPropertyValue(r.getQuotaKey());
        if (actual == null || new BigDecimal(actual.toString()).compareTo(value) != 0) throw new IllegalStateException("配额尚未生效");
    }

    private static boolean expired(PlatformTenantNamespace row) {
        return row.getExpiresAt() != null && row.getExpiresAt() <= System.currentTimeMillis();
    }
    private void requireAdmin() {
        if (!auth.isPlatformSide()) throw new IllegalArgumentException("需要平台管理员权限");
    }

    public NamespaceRecoveryPreview preview(Recovery request) {
        requireAdmin();
        var row = allocations.selectByPrimaryKey(request.allocationId());
        if (row == null || !expired(row) || !Set.of("EXPIRED","STOPPED").contains(row.getLifecycle()))
            throw new IllegalArgumentException("该分配尚未进入待回收状态");
        if (Set.of("default","platform-system").contains(row.getNamespace())) throw new IllegalArgumentException("系统空间受保护");
        if (allocations.selectByClusterAndNs(row.getClusterId(), row.getNamespace()).stream()
                .anyMatch(other -> !row.getId().equals(other.getId())))
            throw new IllegalArgumentException("空间还有其他分配，需先核对归属");
        namespaces.requireEditable(row.getClusterId(), row.getNamespace());
        var preview = lifecycle.recoveryPreview(row.getClusterId(), row.getNamespace());
        if (!preview.uid().equals(row.getNamespaceUid())) throw new IllegalArgumentException("空间 UID 已改变");
        return preview;
    }

    @Transactional
    public void reclaim(Recovery request, boolean delete) {
        requireAdmin();
        var row = allocations.lock(request.allocationId());
        if (row == null) throw new IllegalArgumentException("分配不存在");
        var preview = preview(request);
        if (!preview.uid().equals(request.uid()) || (delete && !row.getNamespace().equals(request.confirmation())))
            throw new IllegalArgumentException("请重新预览并确认空间");
        if (!delete && "STOPPED".equals(row.getLifecycle())) return;
        allocations.transition(row.getId(), delete ? "DELETING" : "STOPPING", null);
    }
}
