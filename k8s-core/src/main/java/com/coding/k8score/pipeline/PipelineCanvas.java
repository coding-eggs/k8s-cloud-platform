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

    /** @param config 字段 key → 用户填的值（**全是字符串**，与表单一致） */
    public record CanvasNode(String id, String type, Map<String, String> config) { }

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
