package com.coding.platformapi.notify;

import com.coding.data.mapper.alert.AlertDeliveryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertDeliveryRecoveryTest {

    /** 早于宽限期、且仍是 PENDING 的行 → 标 FAILED（原因写清楚，UI 才看得懂）。 */
    @Test
    void stale_pending_rows_are_marked_failed() {
        AlertDeliveryMapper mapper = mock(AlertDeliveryMapper.class);
        when(mapper.failStalePending(any(), anyString())).thenReturn(2);

        new AlertDeliveryRecovery(mapper).run(null);

        ArgumentCaptor<Date> before = ArgumentCaptor.forClass(Date.class);
        verify(mapper).failStalePending(before.capture(), org.mockito.ArgumentMatchers.eq("应用重启，投递中断"));
        // 宽限期：cutoff 在"现在 - 5 分钟"附近，不能是"现在"（那会打断在途投递）
        long ageMs = System.currentTimeMillis() - before.getValue().getTime();
        assertThat(ageMs).isGreaterThanOrEqualTo(AlertDeliveryRecovery.STALE_AFTER.toMillis());
    }

    /** 清理失败不能拦住应用启动。 */
    @Test
    void a_failure_does_not_break_startup() {
        AlertDeliveryMapper mapper = mock(AlertDeliveryMapper.class);
        doThrow(new RuntimeException("db down")).when(mapper).failStalePending(any(), anyString());

        new AlertDeliveryRecovery(mapper).run(null);   // 不抛
    }
}
