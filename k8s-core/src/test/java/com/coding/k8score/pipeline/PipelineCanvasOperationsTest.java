package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8score.pipeline.nodes.GitCloneNode;
import com.coding.k8score.pipeline.nodes.ImagePushNode;
import com.coding.k8score.pipeline.nodes.ShellNode;
import com.coding.common.models.pipeline.PipelineConnectionRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanvasOperationsTest {

    private final PipelineNodeRegistry registry =
            new PipelineNodeRegistry(List.of(new GitCloneNode(), new ShellNode(), new ImagePushNode()));
    private final PipelineCanvasOperations ops =
            new PipelineCanvasOperations(registry, new PipelineRenderer(registry));

    @Test
    void nodeTypesExposeSchemaAndPresetsForTheFrontend() {
        // 前端靠它渲染属性面板 —— 加字段不用改前端，就是靠这条
        List<PipelineNodeTypeView> types = ops.nodeTypes();
        assertThat(types).extracting(PipelineNodeTypeView::type)
                .containsExactlyInAnyOrder("git", "shell", "image");

        PipelineNodeTypeView shell = types.stream()
                .filter(t -> t.type().equals("shell")).findFirst().orElseThrow();
        assertThat(shell.fields()).extracting(NodeField::key)
                .containsExactly("preset", "image", "command", "workDir");
        assertThat(shell.presets()).isNotEmpty();
    }

    @Test
    void anImageFieldIsMarkedSoTheFrontendCanAttachAutocomplete() {
        PipelineNodeTypeView shell = ops.nodeTypes().stream()
                .filter(t -> t.type().equals("shell")).findFirst().orElseThrow();
        assertThat(shell.fields()).filteredOn(f -> f.key().equals("image"))
                .singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(NodeField.FieldType.IMAGE));
    }

    @Test
    void renderYamlContainsBothTaskNamesAndRunAfter() {
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}},
                          {"id":"build","type":"shell","config":{"preset":"custom","image":"alpine:3","command":"echo hi"}}],
                 "edges":[{"from":"clone","to":"build"}]}""";
        String yaml = ops.renderYaml(canvas);
        assertThat(yaml).contains("clone").contains("build").contains("runAfter");
    }

    @Test
    void renderRejectsACanvasWithACycle() {
        // ⭐ Review Focus #1 的另一半：前端实时提示之外，**后端也必须拒**
        String canvas = """
                {"nodes":[{"id":"a","type":"git","config":{"repo":"r"}},
                          {"id":"b","type":"shell","config":{"command":"c"}}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"a"}]}""";
        assertThatThrownBy(() -> ops.render(canvas))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("环");
    }

    @Test
    void renderRejectsAnUnknownNodeType() {
        String canvas = """
                {"nodes":[{"id":"a","type":"nope","config":{}}],"edges":[]}""";
        assertThatThrownBy(() -> ops.render(canvas))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    // ---- 带镜像节点的画布：连接是渲染的必需品（2026-10-15 的回归）----

    @Test
    void anImageNodeRendersOnceItsConnectionIsSupplied() {
        // ⭐ 回归：目标镜像地址 = 连接 endpoint + 仓库路径 + tag —— 没有连接就没有地址。
        //    预览/保存那条路径当初**不带连接**，于是「保存一条含构建节点的流水线」必然报
        //    「目标仓库连接不存在或未授权在流水线里使用」。修法就是这条路径也把连接交上去
        //    （endpoint-only，不含凭据内容）。
        String canvas = """
                {"nodes":[{"id":"build","type":"image","config":{"registryConnectionId":"reg-1",
                  "imageName":"proj/app","tagSource":"fixed","fixedTag":"v1"}}],"edges":[]}""";

        String yaml = ops.renderYaml(canvas,
                Map.of("reg-1", new PipelineConnectionRef("reg-1", "harbor.example.com", null, null)));

        // 断言到「地址拼出来了」为止：tag 那一段被 shellQuote 成了 'v1'（引号是为了防注入）
        assertThat(yaml).contains("harbor.example.com/proj/app").contains("buildah push");
    }

    @Test
    void anImageNodeStillRefusesToRenderWithoutItsConnection() {
        // 没连接就**拒渲染**这条行为没变：宁可不出来，也不产出一个必然失败的镜像引用
        String canvas = """
                {"nodes":[{"id":"build","type":"image","config":{"registryConnectionId":"reg-1",
                  "imageName":"proj/app"}}],"edges":[]}""";

        assertThatThrownBy(() -> ops.renderYaml(canvas))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("未授权");
    }
}
