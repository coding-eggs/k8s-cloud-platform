package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8score.pipeline.nodes.GitCloneNode;
import com.coding.k8score.pipeline.nodes.ImagePushNode;
import com.coding.k8score.pipeline.nodes.ShellNode;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
