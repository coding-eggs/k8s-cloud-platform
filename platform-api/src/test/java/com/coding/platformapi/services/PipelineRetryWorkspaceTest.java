package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.pipeline.PipelineRetryWorkspaceDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.platformapi.k8s.K8sPipelineClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRetryWorkspaceTest {

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final NamespaceAccessGuard access = mock(NamespaceAccessGuard.class);
    private final PipelineRunService svc =
            new PipelineRunService(runMapper, logMapper, k8s, credentials, access);

    @Test
    void theListCarriesCountAndTotalBytes() {
        when(k8s.listRetryWorkspaces("c1", "t1", "ns1")).thenReturn(List.of(
                new PipelineRetryWorkspaceDTO("ws-1", "r1", "2026-10-20T00:00:00Z",
                        "2026-10-21T00:00:00Z", 1024L),
                new PipelineRetryWorkspaceDTO("ws-2", "r2", "2026-10-20T01:00:00Z",
                        "2026-10-21T01:00:00Z", 2048L)));

        var out = svc.retryWorkspaces("c1", "ns1", "t1");

        assertThat(out.count()).isEqualTo(2);
        assertThat(out.totalBytes()).isEqualTo(3072L);
        assertThat(out.items()).hasSize(2);
    }

    @Test
    void clearingDeletesEveryListedWorkspace() {
        when(k8s.listRetryWorkspaces("c1", "t1", "ns1")).thenReturn(List.of(
                new PipelineRetryWorkspaceDTO("ws-1", "r1", null, null, 1024L)));

        svc.clearRetryWorkspaces("c1", "ns1", "t1");

        verify(k8s).deleteRetryWorkspace("c1", "t1", "ns1", "ws-1");
    }

    @Test
    void theNamespaceBoundaryIsCheckedFirst() {
        doThrow(new CloudPlatformException(EnumResponseType.NAMESPACE_NOT_ACCESSIBLE, "no"))
                .when(access).assertAccessible("c1", "ns1");

        assertThatThrownBy(() -> svc.retryWorkspaces("c1", "ns1", "t1"))
                .isInstanceOf(CloudPlatformException.class);
    }
}
