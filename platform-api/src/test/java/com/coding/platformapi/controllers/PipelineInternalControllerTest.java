package com.coding.platformapi.controllers;

import com.coding.common.exception.CloudPlatformException;
import com.coding.platformapi.services.PipelineArchiveService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 内部回调端点。**它不在用户 JWT 体系里**（调用方是 k8s-server，没有用户身份）——
 * 唯一的防线是那个共享密钥（spec §4.5⑤）。
 */
class PipelineInternalControllerTest {

    private static PipelineInternalController.FinishedRequest req() {
        return new PipelineInternalController.FinishedRequest("c1", "ns1", "r1", false);
    }

    @Test
    void anEmptyConfiguredTokenRefusesEverything() {
        // ⭐ Review Focus #5：没配密钥 → 拒绝一切，不是"没配就放行"
        PipelineInternalController c = new PipelineInternalController(null, "");
        assertThatThrownBy(() -> c.finished("wrong", req()))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void anEmptyConfiguredTokenAlsoRefusesAnEmptyHeader() {
        // 两边都空**不算匹配** —— 那是 fail-open 而不是 fail-closed
        PipelineInternalController c = new PipelineInternalController(null, "");
        assertThatThrownBy(() -> c.finished("", req()))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> c.finished(null, req()))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aWrongTokenIsRefused() {
        PipelineInternalController c = new PipelineInternalController(null, "s3cret");
        assertThatThrownBy(() -> c.finished("nope", req()))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aMissingHeaderIsRefusedNotTreatedAsEmptyMatch() {
        PipelineInternalController c = new PipelineInternalController(null, "s3cret");
        assertThatThrownBy(() -> c.finished(null, req()))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void aRefusedRequestNeverReachesTheArchiveService() {
        PipelineArchiveService archive = mock(PipelineArchiveService.class);
        PipelineInternalController c = new PipelineInternalController(archive, "s3cret");

        assertThatThrownBy(() -> c.finished("nope", req()))
                .isInstanceOf(CloudPlatformException.class);

        verifyNoInteractions(archive);
    }

    @Test
    void theRightTokenDelegatesToTheArchiveService() {
        PipelineArchiveService archive = mock(PipelineArchiveService.class);
        PipelineInternalController c = new PipelineInternalController(archive, "s3cret");

        c.finished("s3cret", req());

        verify(archive).archive("c1", "ns1", "r1", false);
    }

    @Test
    void theTimeoutFlagIsCarriedThroughToTheArchive() {
        // ⭐ 平台判超时时 Tekton 那边**还在跑**，光看对象的 condition 分不出
        // 「用户取消」与「平台判超时」—— 所以这个标志必须跟着请求走（spec §4.2）
        PipelineArchiveService archive = mock(PipelineArchiveService.class);
        PipelineInternalController c = new PipelineInternalController(archive, "s3cret");

        c.finished("s3cret", new PipelineInternalController.FinishedRequest("c1", "ns1", "r1", true));

        verify(archive).archive("c1", "ns1", "r1", true);
    }
}
