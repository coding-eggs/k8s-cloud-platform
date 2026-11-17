package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.platformapi.models.PipelineSourceRefDTO;
import com.coding.platformapi.outbound.OutboundProxy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 源码预览的**纯解析**部分（不碰网络）：画布读取、sha 形状、基线解析。
 *
 * <p>真正访问仓库的那部分（ls-remote / 浅取）只能在真环境验证 —— 这里的护栏是
 * 「字段名与画布契约一致」和「失败一定要带 error，而不是假装有 sha」。
 */
class SourcePreviewServiceTest {

    private final SourcePreviewService svc = new SourcePreviewService(
            mock(CredentialService.class), mock(PlatformPipelineRunMapper.class),
            new OutboundProxy("", 0, ""), 30000);

    @Test
    void gitNodesAreReadFromTheCanvasContractOnly() {
        String canvas = """
                {"nodes":[
                  {"id":"git-1","type":"git","config":{"repo":"https://gitlab.x/g/r.git","revision":"main","connectionId":"conn-1"}},
                  {"id":"sh-1","type":"shell","config":{"command":"echo hi"}},
                  {"id":"git-2","type":"git","config":{"repo":"https://github.com/a/b.git"}}
                ],"edges":[]}""";

        List<SourcePreviewService.GitNode> nodes = svc.gitNodes(canvas);

        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).nodeId()).isEqualTo("git-1");
        assertThat(nodes.get(0).repo()).isEqualTo("https://gitlab.x/g/r.git");
        assertThat(nodes.get(0).revision()).isEqualTo("main");
        assertThat(nodes.get(0).connectionId()).isEqualTo("conn-1");
        // 留空的分支/连接要落成空串，而不是 null（下游直接拿去判断 isEmpty）
        assertThat(nodes.get(1).revision()).isEmpty();
        assertThat(nodes.get(1).connectionId()).isEmpty();
    }

    @Test
    void aGitNodeWithoutARepoFailsLoudlyInsteadOfReturningASha() {
        List<PipelineSourceRefDTO> out = svc.preview(null,
                "{\"nodes\":[{\"id\":\"git-1\",\"type\":\"git\",\"config\":{}}],\"edges\":[]}", "t1");

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.sha()).isNull();
            assertThat(s.error()).contains("没有填仓库地址");
        });
    }

    @Test
    void onlyFullFortyCharacterHexCountsAsAPin() {
        assertThat(SourcePreviewService.isFullSha("3f2c1ab9d4e5f60718293a4b5c6d7e8f90123456")).isTrue();
        // 短 sha 只在界面上好看，**不能当 pin**：它不能唯一确定一个提交
        assertThat(SourcePreviewService.isFullSha("3f2c1ab")).isFalse();
        assertThat(SourcePreviewService.isFullSha("")).isFalse();
        assertThat(SourcePreviewService.isFullSha(null)).isFalse();
        assertThat(SourcePreviewService.isFullSha("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ")).isFalse();
    }

    @Test
    void theBaselineIsTheShaRecordedForThatNodeInPreviousRuns() {
        String sourceJson = """
                [{"nodeId":"git-1","sha":"9a01c2f3d4e5f60718293a4b5c6d7e8f90123456"},
                 {"nodeId":"git-2","sha":null}]""";

        assertThat(SourcePreviewService.shaOfNode(sourceJson, "git-1"))
                .isEqualTo("9a01c2f3d4e5f60718293a4b5c6d7e8f90123456");
        assertThat(SourcePreviewService.shaOfNode(sourceJson, "git-2")).isNull();
        assertThat(SourcePreviewService.shaOfNode(sourceJson, "nope")).isNull();
        assertThat(SourcePreviewService.shaOfNode(null, "git-1")).isNull();
        // 老数据/手工改过：解析不了就当没有，不能让预览整个失败
        assertThat(SourcePreviewService.shaOfNode("{not json", "git-1")).isNull();
    }
}
