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
     */
    public record CanvasNode(String id, String type, String name, Map<String, String> config) { }

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
