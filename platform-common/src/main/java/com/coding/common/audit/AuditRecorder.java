package com.coding.common.audit;

import com.coding.data.models.system.PlatformAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 审计记录的写入门面（servlet 业务服务共用）：**fail-open 包装**。
 *
 * <p>实际写库经 {@link AuditWriter}（独立事务）—— 本类**不直接调 mapper.insert**，
 * 否则 REQUIRES_NEW 会因为同类自调用而失效（见 AuditWriter 的类注释）。
 *
 * <p>403（DENIED）不在这里：接口级判定与拒绝审计都在平台网关（{@code GatewayDeniedAuditor}）。
 */
@Slf4j
@RequiredArgsConstructor
public class AuditRecorder {

    /** 身份取不到时记的字面量（不记 NULL —— 与「平台侧无租户操作」的 NULL 语义区分开） */
    public static final String UNKNOWN_ACTOR = "unknown";

    private final AuditWriter writer;

    /**
     * fail-open：审计写不进去只记错误日志，**绝不改变业务结果**（spec「写入契约」）。
     */
    public void record(PlatformAuditLog record) {
        try {
            writer.write(record);
        } catch (Exception e) {
            log.error("审计写入失败 action={} result={} path={}",
                    record.getAction(), record.getResult(), record.getRequestPath(), e);
        }
    }
}
