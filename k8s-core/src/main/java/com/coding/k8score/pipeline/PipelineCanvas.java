package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 画布：**流水线的唯一真相源**（spec §3.2）。集群里不留 {@code Pipeline} 对象，
 * 每次运行即时把它渲染成 {@code PipelineSpec}。
 */
public record PipelineCanvas(List<CanvasNode> nodes, List<CanvasEdge> edges) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 画布上的一个节点。
     *
     * @param id     <b>身份</b> —— 进 Tekton 的 task 名，也是日志与运行状态的键（{@code stepsJson} 的 key）。
     *               前端生成，形如 {@code git-l8x31}：**不可读是它的必然**，因为它得满足 K8s 的
     *               DNS-1123（小写字母/数字/连字符），中文名根本进不来。
     * @param name   <b>显示名</b> —— 用户在属性面板里起的名字，**可空、可中文**。给人看的那一个：
     *               画布卡片、运行详情、以及渲染出的 {@code taskSpec.displayName}。
     *               空 = 没起名，各处一律退回显示 {@code id}。
     *               ⚠️ 它**不参与任何键**：改个名字不会动集群对象名，也不会让旧运行的日志/状态对不上。
     * @param config 字段 key → 用户填的值（**全是字符串**，与表单一致）
     * @param position 画布上的**显示坐标**（前端拖出来的）。纯展示：渲染器不看它，
     *                 集群里跑出来的东西与它无关。可空 —— 加这个字段之前存下来的画布没有坐标，
     *                 前端会退回自动排布。
     *                 ⚠️ 必须存下来：不存就等于「每次打开都换一张图」，用户手摆的布局白摆
     *                 （2026-10-15 用户报「保存后再编辑，节点叠在一起了」）。
     * @param onError <b>失败也继续</b>。取值见 {@link PipelineRenderer#CONTINUE}；空 = 照常失败
     *                （这一节点挂了就跳过它所有的下游）。它落在 Tekton 的 {@code task.onError} 上。
     * @param finallyTask <b>收尾执行</b>。{@code true} = 这个节点挪进 {@code finally[]} ——
     *                不参与顺序、前面的节点怎么走它都要跑（跑不跑由 {@code finallyWhen} 定）。
     *                ⚠️ 用装箱的 {@code Boolean}：老画布（这个字段还不存在时存的）反序列化出来是
     *                {@code null}，与基本类型的 {@code false} 在渲染上等价，但不会在 Jackson 那里炸。
     * @param finallyWhen 收尾节点的**执行时机**（见 {@code PipelineRenderer} 的 {@code WHEN_*} 常量）。
     *                    空 = 总是执行（就不用写 {@code when}）。只在 {@code finallyTask} 为真时有意义。
     */
    public record CanvasNode(String id, String type, String name, Map<String, String> config,
                             Position position, String onError, Boolean finallyTask, String finallyWhen) {

        /** 没有布局信息（老画布 / 只关心「跑什么」的地方）—— 前端会退回自动排布。 */
        public CanvasNode(String id, String type, String name, Map<String, String> config) {
            this(id, type, name, config, null);
        }

        /** 有布局、但没有任何运行控制（老画布的常态）。 */
        public CanvasNode(String id, String type, String name, Map<String, String> config,
                          Position position) {
            this(id, type, name, config, position, null, null, null);
        }

        /** 这个节点是不是收尾节点（{@code null} 与 {@code false} 一样）。 */
        public boolean isFinally() {
            return Boolean.TRUE.equals(finallyTask);
        }

        /** 这个节点失败后要不要继续（{@code onError} 是不是 {@code continue}）。 */
        public boolean continuesOnError() {
            return PipelineRenderer.CONTINUE.equals(onError);
        }
    }

    /** 画布坐标。整数像素，前端 Vue Flow 的坐标系。 */
    public record Position(double x, double y) { }

    public record CanvasEdge(String from, String to) { }

    public static String toJson(PipelineCanvas c) {
        return JSON.writeValueAsString(c);
    }

    public static PipelineCanvas fromJson(String json) {
        try {
            PipelineCanvas c = JSON.readValue(json, PipelineCanvas.class);
            if (c == null) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID, "画布为空");
            }
            return c;
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            // ⚠️ 不能"解析失败就当空画布" —— 那会让用户以为自己的流水线没了
            throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID,
                    "画布内容无法解析");
        }
    }
}
