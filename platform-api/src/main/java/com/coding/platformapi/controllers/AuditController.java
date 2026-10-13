package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.data.models.system.PlatformAuditLogQuery;
import com.coding.platformapi.models.AuditPurgeRequest;
import com.coding.platformapi.models.AuditQueryRequest;
import com.coding.platformapi.services.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计日志（平台管理面，仅 admin）。本层只做 HTTP 绑定与 {@link ResponseData} 包装；
 * 查询与删除都在 {@link AuditService}（分层约定见 docs/development/backend-layering.md）。
 *
 * <p>权限行见 V2026_10_13_1：{@code /audit/list} → {@code platform:audit:read}，
 * {@code /audit/purge} → {@code platform:audit:purge}。两条都必须有行，否则启动期交叉校验拒启。
 *
 * <p>没有单条详情端点：列表就返回全部 18 个字段，前端行展开即可（spec §6 偏离记录）。
 */
@Tag(name = "审计日志", description = "写操作与 403 被拒请求的审计查询")
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @Operation(summary = "审计列表", description = "按 occurred_at 倒序，游标分页；返回全部字段")
    @PostMapping("/list")
    public ResponseData<List<PlatformAuditLog>> list(@RequestBody AuditQueryRequest req) {
        return new ResponseData<>(auditService.query(toQuery(req)));
    }

    @Operation(summary = "按时间范围删除", description = "闭开区间 [from, to)；返回实际删除行数")
    @PostMapping("/purge")
    public ResponseData<Integer> purge(@RequestBody AuditPurgeRequest req) {
        return new ResponseData<>(auditService.purge(req.getFrom(), req.getTo()));
    }

    private PlatformAuditLogQuery toQuery(AuditQueryRequest req) {
        PlatformAuditLogQuery q = new PlatformAuditLogQuery();
        q.setFrom(req.getFrom());
        q.setTo(req.getTo());
        q.setActorUsername(req.getActorUsername());
        q.setClusterId(req.getClusterId());
        q.setNamespace(req.getNamespace());
        q.setResult(req.getResult());
        q.setAction(req.getAction());
        q.setCursorOccurredAt(req.getCursorOccurredAt());
        q.setCursorId(req.getCursorId());
        q.setLimit(req.getLimit());
        return q;
    }
}
