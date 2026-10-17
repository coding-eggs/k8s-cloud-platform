package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.data.models.k8s.PlatformAlertEventQuery;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.models.AlertQueryRequest;
import com.coding.platformapi.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 告警列表。
 *
 * <p><b>租户边界在这一层</b>（照 {@code NamespaceAllocationService.list} 的口径）：
 * token 带 tenantInfo 时**入参一概不算数**，只按该租户在本集群的命名空间分配去查；
 * 没有租户 claim（平台管理员）才不过滤。
 *
 * <p>⚠️ 租户在本集群一个命名空间都没分配 → 传**空 list**（不是 null）。
 * 空 list 在 mapper 里变成 {@code and 1 = 0}，返回空集；传 null 会退化成"不过滤"，
 * 等于让这个租户看到全平台的告警。
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final PlatformAlertEventMapper mapper;
    private final PlatformTenantNamespaceMapper allocs;
    private final AuthContext authContext;

    public List<PlatformAlertEvent> list(AlertQueryRequest req) {
        PlatformAlertEventQuery q = new PlatformAlertEventQuery();
        q.setClusterId(req.getClusterId());
        q.setFrom(req.getFrom());
        q.setTo(req.getTo());
        q.setNamespace(req.getNamespace());
        q.setStatus(req.getStatus());
        q.setSeverity(req.getSeverity());
        q.setAlertname(req.getAlertname());
        q.setExcludeWatchdog(!Boolean.TRUE.equals(req.getShowWatchdog()));   // 默认排除
        q.setCursorStartsAt(req.getCursorStartsAt());
        q.setCursorId(req.getCursorId());
        q.setLimit(clamp(req.getLimit()));

        TokenUserInfo info = authContext.current();
        String tokenTenantId = (info != null && info.getTenantInfo() != null)
                ? info.getTenantInfo().getTenantId() : null;
        if (StringUtils.hasText(tokenTenantId)) {
            List<String> ns = allocs.listByTenantAndCluster(tokenTenantId, req.getClusterId()).stream()
                    .map(PlatformTenantNamespace::getNamespace)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            q.setNamespaces(ns);
        }
        // 无租户 claim（平台管理员）→ namespaces 保持 null = 不过滤
        return mapper.query(q);
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
