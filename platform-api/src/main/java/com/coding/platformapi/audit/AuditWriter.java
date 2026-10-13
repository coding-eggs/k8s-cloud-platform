package com.coding.platformapi.audit;

import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.models.system.PlatformAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计记录的**独立事务**写入点。
 *
 * <h3>为什么单独一个 bean</h3>
 * Spring 的事务代理只在**跨 bean 调用**时生效。若把 {@code write} 放在 {@code AuditService} 里
 * 由同类方法调用，{@code REQUIRES_NEW} 会被静默绕过 —— 那时审计记录会加入业务事务，
 * 业务一回滚就把记录一起带走，恰好丢掉最想留的那条 FAILED。
 * 同族先例见 {@code PermissionRowWriter} 的类注释。
 *
 * <h3>REQUIRES_NEW 的第二个作用</h3>
 * 审计写失败不会污染业务事务（不会把它标记成 rollback-only），这是 fail-open 能成立的前提。
 */
@Component
@RequiredArgsConstructor
public class AuditWriter {

    private final PlatformAuditLogMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(PlatformAuditLog record) {
        mapper.insert(record);
    }
}
