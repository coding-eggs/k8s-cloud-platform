package com.coding.k8score.converter.impl.pipeline;

import com.coding.common.models.k8s.dto.PipelineRunDTO;
import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.tekton.v1.PipelineRun;
import io.fabric8.tekton.v1.PipelineRunStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** Tekton {@code PipelineRun} ↔ DTO。状态映射是**唯一有规则**的地方，所以它单测。 */
@Component
public class PipelineRunConverter {

    /**
     * 「这个节点**根本没跑**」—— Tekton 对跳过的 PipelineTask **不建 TaskRun**，所以它不在下面
     * 那套 condition 映射里，得由 {@code status.skippedTasks[]} 补进来
     * （见 {@code PipelineRunOperations.steps}）。
     *
     * <p>两种情形会走到这里：上游没成功被级联跳过、以及 finally 节点的执行时机不满足。
     *
     * <p>⚠️ 与前端 {@code PipelineNodeVue} 的 {@code STATUS_TEXT} 成对 —— 加状态要同时加两边。
     */
    public static final String SKIPPED = "SKIPPED";

    /**
     * Tekton 用 conditions 表达结果，平台用五个状态。映射：
     * <ul>
     *   <li>没有 condition（还没被 reconciler 处理）→ {@code RUNNING}</li>
     *   <li>{@code Succeeded=True} → {@code SUCCEEDED}</li>
     *   <li>⚠️ {@code Succeeded=Unknown}（reason=Running）→ <b>{@code RUNNING}</b> ——
     *       Tekton 在**运行中**给的就是 Unknown，把它算成 FAILED 会让归档的
     *       「未终结不归档」守卫整个失效：一条正在跑的运行会被写出 FAILED 的结论、
     *       日志抓半截、**对象被删掉**，正在跑的构建被打断</li>
     *   <li>{@code Succeeded=False} 且 reason 含 Cancelled → {@code CANCELLED}</li>
     *   <li>{@code Succeeded=False} 且 reason 含 Timeout → {@code TIMEOUT}</li>
     *   <li>其余 {@code Succeeded=False} → {@code FAILED}</li>
     * </ul>
     * ⚠️ 「平台取消」与「平台判超时」都要能看出来 —— 运行历史里它们对用户是两件事。
     *
     * <p>⚠️ conditions 的元素类型是 {@link Condition}（`io.fabric8.knative.pkg.apis`），
     * <b>不是</b> `io.fabric8.tekton.v1` 下的某个类型 —— Tekton 的 CRD 直接复用了 Knative 的
     * Condition 结构，tekton-model 里没有自己的 Condition 类。
     */
    public PipelineRunDTO toDto(PipelineRun pr) {
        PipelineRunDTO dto = new PipelineRunDTO();
        if (pr == null) {
            return dto;
        }
        dto.setName(pr.getMetadata() == null ? null : pr.getMetadata().getName());
        dto.setNamespace(pr.getMetadata() == null ? null : pr.getMetadata().getNamespace());
        dto.setLabels(pr.getMetadata() == null ? null : pr.getMetadata().getLabels());

        PipelineRunStatus st = pr.getStatus();
        dto.setStatus(mapStatus(st));
        if (st != null) {
            dto.setStartTime(st.getStartTime());
            dto.setCompletionTime(st.getCompletionTime());
            dto.setReason(reasonOf(st));
        }
        return dto;
    }

    public PipelineRun toTekton(PipelineRunDTO dto) {
        PipelineRun pr = new PipelineRun();
        // ⚠️ tekton-model **不初始化 metadata**（`new PipelineRun().getMetadata()` 是 null，
        // 与 core 模型不同）—— 不先 setMetadata 就会 NPE。
        pr.setMetadata(new ObjectMeta());
        pr.getMetadata().setName(dto.getName());
        pr.getMetadata().setNamespace(dto.getNamespace());
        // ⚠️ create 不写 status —— 它是子资源，写了也会被忽略，还会掩盖真实的初始状态
        return pr;
    }

    static String mapStatus(PipelineRunStatus st) {
        return mapConditions(st == null ? null : st.getConditions());
    }

    /**
     * conditions → 平台状态。**PipelineRun 与 TaskRun 共用这一份** ——
     * 两者用的是同一个 Knative Condition 结构、同一套 reason 约定，
     * 分成两份只会有一次改一处忘一处。
     */
    public static String mapConditions(List<Condition> conditions) {
        String reason = reasonOf(conditions);
        String cond = conditionStatus(conditions);
        if ("True".equals(cond)) {
            return "SUCCEEDED";
        }
        if (!"False".equals(cond)) {
            // null（还没有 condition）与 "Unknown"（reconciler 正在跑）都是「还没结束」
            return "RUNNING";
        }
        if (reason != null && reason.toLowerCase().contains("cancel")) {
            return "CANCELLED";
        }
        if (reason != null && reason.toLowerCase().contains("timeout")) {
            return "TIMEOUT";
        }
        return "FAILED";
    }

    private static String conditionStatus(List<Condition> conditions) {
        Condition c = succeededCondition(conditions);
        return c == null ? null : c.getStatus();
    }

    private static String reasonOf(List<Condition> conditions) {
        Condition c = succeededCondition(conditions);
        return c == null ? null : c.getReason();
    }

    private static String reasonOf(PipelineRunStatus st) {
        return reasonOf(st == null ? null : st.getConditions());
    }

    private static String conditionStatus(PipelineRunStatus st) {
        return conditionStatus(st == null ? null : st.getConditions());
    }

    private static Condition succeededCondition(PipelineRunStatus st) {
        return succeededCondition(st == null ? null : st.getConditions());
    }

    private static Condition succeededCondition(List<Condition> conditions) {
        if (conditions == null) {
            return null;
        }
        return conditions.stream().filter(c -> "Succeeded".equals(c.getType())).findFirst().orElse(null);
    }
}
