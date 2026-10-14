package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点分派。**这是「有哪些节点」的唯一真相源** —— 调用方不要再手写一份 type 清单
 * （S3.1 终审删掉 {@code REGISTRY_TYPES} 时反对的就是那个形状）。
 */
@Component
public class PipelineNodeRegistry {

    private final Map<String, PipelineNode> byType = new HashMap<>();

    public PipelineNodeRegistry(List<PipelineNode> nodes) {
        for (PipelineNode n : nodes) {
            // ⚠️ 重复 type 是**编程错误**，不能"后者覆盖" —— 那会让一个节点在画布上可用、
            // 渲染时静默变成另一个。构建期就炸掉。
            if (byType.put(n.type(), n) != null) {
                throw new IllegalStateException("流水线节点 type 重复：" + n.type());
            }
        }
    }

    public PipelineNode of(String type) {
        PipelineNode n = byType.get(type);
        if (n == null) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_NODE_UNSUPPORTED,
                    "不支持的流水线节点类型：" + type);
        }
        return n;
    }

    public boolean supports(String type) {
        return type != null && byType.containsKey(type);
    }

    public List<PipelineNode> all() {
        return List.copyOf(byType.values());
    }
}
