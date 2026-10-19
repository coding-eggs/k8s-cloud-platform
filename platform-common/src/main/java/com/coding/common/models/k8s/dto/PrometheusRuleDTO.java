package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * PrometheusRule DTO（{@code monitoring.coreos.com/v1} CRD，**命名空间级**、租户域 —— 同 ServiceMonitor）。
 * <p>端点走 k8s-server {@code /prometheusrules}（{@code AbstractNamespacedResourceController} → 分配表三元组边界）。
 *
 * <h2>为什么必须 fetch-overlay</h2>
 * CRD 里 {@code spec.groups} 与 {@code group.rules} <b>都没有 listType 标记 → 默认 atomic</b>。
 * 对 atomic list，SSA 把整个列表当一个值拥有 —— 平台 apply 一次不带某个子字段，该子字段就被抹掉
 * （memory: fabric8-ssa-generic-crd 记的同一个坑；HTTPRoute 的 {@code spec.rules} 同形）。
 * 故 {@code PrometheusRuleConverter.convertForUpdate} 按 <b>组名</b> 与 <b>规则名</b> 对齐做 overlay。
 *
 * <p>本版**已建模全部已知字段**（{@code kubectl explain prometheusrule} 核对过），overlay 仍然要写：
 * 显式 round-trip 比依赖 schema 语义推断更可测，也对 CRD 版本漂移更稳健（同 GatewayConverter 的理由）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PrometheusRuleDTO extends BaseResources {

    /** spec.groups（必填，至少 1 组） */
    private List<Group> groups;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    /**
     * **这条规则在集群里跑得怎么样**（仅查询返回）—— 见 S2.2 块 2。
     *
     * <p>⚠️ 它**不是** K8s 对象的一部分：由 platform-api 读 Prometheus 的 {@code /api/v1/rules}
     * 后附着上来。拉不到就是 {@code null}，UI 显示「状态未知」，**CRUD 照常**。
     * converter 不映射本字段（它只在 list/get 的出站方向被填充）。
     */
    private RuleRuntime runtime;

    /**
     * 平台侧字段（**不是 K8s 对象的一部分**）：规则选中的通知方式 code。
     * converter 不映射；保存时由 {@code RuleNotifyBinder} 写绑定表，并由
     * {@code PrometheusRuleService} 代打 {@code labels.platform_notify} 后剥离，不下发。
     */
    private String notifyChannel;

    /** 平台侧字段：收件人用户 id 列表（只进绑定表，不下发） */
    private List<String> notifyUserIds;

    @Override
    public String getApiPath() {
        return "/prometheusrules";
    }

    /** spec.groups[] 项。字段全集来自 {@code kubectl explain prometheusrule.spec.groups}。 */
    @Data
    public static class Group {

        /** 组名（必填；同一 PrometheusRule 内唯一 —— overlay 的**对齐键**） */
        private String name;

        /** 求值间隔（如 30s） */
        private String interval;

        /** 规则求值时间戳的偏移（如 1m） */
        private String queryOffset;

        /** 一组告警/序列的产出上限（Prometheus ≥ 2.31） */
        private Integer limit;

        /** 组级标签（rule 级优先）；Prometheus ≥ 3.0，Thanos Ruler 忽略 */
        private Map<String, String> labels;

        /** 仅 ThanosRuler 用，Prometheus 忽略。本平台只读保留，不给编辑入口 */
        private String partialResponseStrategy;

        private List<Rule> rules;
    }

    /**
     * group.rules[] 项。字段全集来自 {@code kubectl explain prometheusrule.spec.groups.rules}。
     * <p>{@code alert} 与 {@code record} **恰好给一个**（CRD 约束，平台在 PrometheusRuleService 里前置拦截）。
     */
    @Data
    public static class Rule {

        /** 告警名（与 record 二选一）。overlay 的对齐键之一 */
        private String alert;

        /** 记录到的指标名（与 record 二选一）。overlay 的对齐键之一 */
        private String record;

        /**
         * PromQL 表达式（必填）。
         * <p>⚠️ CRD 里是 {@code IntOrString} —— 上游允许裸数字（{@code expr: 1} 等价于 {@code vector(1)}）。
         * DTO 用 String；converter 读回来时把数字转成字符串，写出时按字符串写。
         */
        private String expr;

        /** 持续多久才算 firing（如 5m） */
        private String for_;

        /** 条件消失后继续 firing 多久（Prometheus ≥ 2.38） */
        private String keepFiringFor;

        /**
         * 平台侧便捷字段：**严重度下拉**。
         *
         * <p>它本质上就是 K8s 的 {@code labels.severity}（Prometheus 生态的约定位置），
         * 平台在保存时把它拼进 labels、读出来时再摘回本字段供编辑页回显 ——
         * 这样编辑页可以给一个固定选项的下拉，而不是让用户在通用标签编辑器里手打。
         *
         * <p>⚠️ 保存时本字段为空的含义是「**清掉 severity 标签**」（下拉被清空），
         * 因为读接口一定会先把它回填进来 —— 老客户端不传这个字段等于清空。
         */
        private String severity;

        /**
         * 平台侧便捷字段：**摘要**。
         *
         * <p>本质是 {@code annotations.summary}（Prometheus 生态的约定位置，通知正文与告警列表都从那里读）。
         * 保存时平台拼进 annotations，读详情时再摘回本字段供编辑页回显，
         * 这样摘要有一个专门的输入框，而不是藏在通用注解编辑器里。
         *
         * <p>⚠️ 保存时本字段为空 = **清掉 summary 注解**（同 severity 的口径）。
         */
        private String summary;

        private Map<String, String> labels;

        /** 仅 alert 规则有效 */
        private Map<String, String> annotations;
    }

    /**
     * 运行状态（仅查询返回）。{@code null} = 没探测或探测失败。
     * <p>三态判据见 {@code RuleRuntimeMatcher} 与 spec §6。
     */
    @Data
    public static class RuleRuntime {

        /** loaded = 在 /api/v1/rules 里对齐上了；missing = 没对齐上且过了宽限期；unknown = 拉不到 */
        private String loadState;

        /** 逐条规则的运行状态；loadState != loaded 时为空 */
        private List<RuleHealth> rules;
    }

    /** 单条规则的运行状态。键 (group, name) 与 {@link Group#getName()} / {@link Rule#getAlert()} 对齐。 */
    @Data
    public static class RuleHealth {

        /** spec.groups[].name */
        private String group;

        /** alert 的告警名 / record 的记录名 */
        private String name;

        /** ok | err | unknown */
        private String health;

        /** health=err 时 Prometheus 给的原文 */
        private String lastError;

        /** inactive | pending | firing；recording 规则恒为 null */
        private String state;

        /** 活跃告警数；recording 规则恒为 null */
        private Integer activeAlerts;
    }

}
