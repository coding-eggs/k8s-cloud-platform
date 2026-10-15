package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.PipelineNode;
import com.coding.k8score.pipeline.PipelineNodeRegistry;

import java.util.List;

/**
 * 测试用的**内置节点全集**。
 *
 * <p>生产里由 Spring 收集所有 {@code PipelineNode} bean（加节点不用改任何清单），
 * 但测试要手搓注册表 —— 清单散在三处的话，加节点时总有一处忘了改、而"忘了"不会报错，
 * 只会让那几处测试少测一个节点。所以清单收在这里一份。
 *
 * <p>⚠️ 加了新节点这里也要加：{@code BuiltinNodesTest} 与 {@code PipelineCanvasOperationsTest}
 * 的**类型清单断言**会因此变红，逼你确认一次（这正是它们该干的事）。
 */
public final class AllNodes {

    private AllNodes() {
    }

    public static List<PipelineNode> list() {
        return List.of(
                new GitCloneNode(), new ShellNode(), new ImagePushNode(),
                new JavaBuildNode(), new NodeBuildNode(), new GoBuildNode(), new PythonBuildNode());
    }

    public static PipelineNodeRegistry registry() {
        return new PipelineNodeRegistry(list());
    }
}
