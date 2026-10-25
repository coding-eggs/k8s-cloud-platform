package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformApplicationOperationMapper;
import com.coding.data.models.system.PlatformApplicationOperation;
import com.coding.platformapi.k8s.K8sServerGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationOperationWorkerTest {

    private ApplicationDeployService deploy;
    private PlatformApplicationOperationMapper operations;
    private ApplicationDeployCredentials credentials;
    private ApplicationOperationWorker worker;

    @BeforeEach
    void setUp() {
        deploy = mock(ApplicationDeployService.class);
        operations = mock(PlatformApplicationOperationMapper.class);
        credentials = mock(ApplicationDeployCredentials.class);
        worker = new ApplicationOperationWorker(deploy, operations, credentials);
    }

    private static PlatformApplicationOperation op() {
        PlatformApplicationOperation op = new PlatformApplicationOperation();
        op.setId(7L);
        op.setAppId("app_1");
        return op;
    }

    /** 平台重启后内存里的凭据没了：明确失败，不要让操作永远 RUNNING。 */
    @Test
    void failsWhenCredentialsAreGone() {
        PlatformApplicationOperation op = op();
        when(operations.selectFirstActive()).thenReturn(op);

        worker.tick();

        verify(deploy).failWithoutCredentials(op);
        verify(deploy, never()).advance(any());
    }

    /** 正常路径：worker 线程里必须能拿到发起人的 Authorization（否则 k8s-server 401）。 */
    @Test
    void advancesInsideDelegatedAuthorization() {
        PlatformApplicationOperation op = op();
        when(operations.selectFirstActive()).thenReturn(op);
        when(credentials.get(7L)).thenReturn("Bearer user-token");

        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(K8sServerGateway.delegatedAuthorization());
            return null;
        }).when(deploy).advance(any());

        worker.tick();

        assertThat(seen.get()).isEqualTo("Bearer user-token");
        assertThat(K8sServerGateway.delegatedAuthorization()).isNull();   // 作用域已清理
        verify(deploy, never()).failWithoutCredentials(any());
    }

    @Test
    void doesNothingWhenQueueIsEmpty() {
        worker.tick();
        verify(deploy, never()).advance(any());
    }
}
