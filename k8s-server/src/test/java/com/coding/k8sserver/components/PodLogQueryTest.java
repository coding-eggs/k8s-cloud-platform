package com.coding.k8sserver.components;

import io.fabric8.kubernetes.client.dsl.BytesLimitTerminateTimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodLogQueryTest {

    @Test
    void previousMapsToTerminatedOnTheFabric8Chain() {
        // ⭐ fabric8 里 previous **不叫** previous()，叫 terminated()。
        //    实测反编译：terminated() → withTerminatedStatus(true) → URL 上的 `&previous=true`。
        //    这一行是整批里唯一一眼看不出对错的地方，所以钉住它。
        BytesLimitTerminateTimeTailPrettyLoggable loggable =
                mock(BytesLimitTerminateTimeTailPrettyLoggable.class);
        TimeTailPrettyLoggable terminated = mock(TimeTailPrettyLoggable.class);
        when(loggable.terminated()).thenReturn(terminated);

        assertThat(PodLogQuery.withPrevious(loggable, true)).isSameAs(terminated);
        verify(loggable).terminated();
    }

    @Test
    void withoutPreviousTheChainIsLeftAlone() {
        // 防"顺手一直带着 previous" —— 那会让所有日志都变成上一代的
        BytesLimitTerminateTimeTailPrettyLoggable loggable =
                mock(BytesLimitTerminateTimeTailPrettyLoggable.class);

        assertThat(PodLogQuery.withPrevious(loggable, false)).isSameAs(loggable);
        verify(loggable, never()).terminated();
    }
}
