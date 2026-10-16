package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.BytesLimitTerminateTimeTailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.Loggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void aHealthyStreamIsJustCopied() throws Exception {
        TimeTailPrettyLoggable loggable = mock(TimeTailPrettyLoggable.class);
        Loggable pretty = mock(Loggable.class);
        when(loggable.withPrettyOutput()).thenReturn(pretty);
        when(pretty.getLogInputStream())
                .thenReturn(new ByteArrayInputStream("hi\n".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        PodLogQuery.stream(loggable, null, null, null, out);

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("hi\n");
    }

    @Test
    void aKubeletRejectionSurfacesItsOwnMessageInsteadOfAGenericOne() {
        // ⭐ 2026-10-16 整体评审发现：lambda 里抛出的**非** CloudPlatformException 会被
        //    GlobalExceptionHandler.handleOtherError 接住 —— 它返回 new ResponseData<>(5000, null)，
        //    于是 msg 走 getMsgByCode(5000) = 通用文案（「服务端异常」），**而且 HTTP 200**。
        //    后果很具体：用户点「上一次」想看"为什么死的"，看到的是"服务端异常"。
        //    翻成 CloudPlatformException 之后走 handleMovieException，msg **原样**回给前端。
        TimeTailPrettyLoggable loggable = mock(TimeTailPrettyLoggable.class);
        Loggable pretty = mock(Loggable.class);
        Status status = new Status();
        status.setMessage("previous terminated container \"app\" not found");
        when(loggable.withPrettyOutput()).thenReturn(pretty);
        when(pretty.getLogInputStream())
                .thenThrow(new KubernetesClientException("Bad Request", 400, status));

        assertThatThrownBy(() -> PodLogQuery.stream(loggable, null, null, null, OutputStream.nullOutputStream()))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("previous terminated container");
    }

    @Test
    void aRejectionWithoutAStatusFallsBackToTheExceptionMessage() {
        // k8s 没给 Status 时别把 msg 变成 null（那会让前端显示空字符串）
        TimeTailPrettyLoggable loggable = mock(TimeTailPrettyLoggable.class);
        Loggable pretty = mock(Loggable.class);
        when(loggable.withPrettyOutput()).thenReturn(pretty);
        when(pretty.getLogInputStream()).thenThrow(new KubernetesClientException("连接被拒"));

        assertThatThrownBy(() -> PodLogQuery.stream(loggable, null, null, null, OutputStream.nullOutputStream()))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("连接被拒");
    }
}
