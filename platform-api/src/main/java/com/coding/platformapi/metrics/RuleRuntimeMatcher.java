package com.coding.platformapi.metrics;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Prometheus {@code /api/v1/rules} 的规则**对齐**到平台手上的 PrometheusRule 对象。
 *
 * <h2>为什么不能解析 file</h2>
 * prometheus-operator 把每条 CR 渲染成一个文件，路径形如
 * {@code …/rulefiles-0/<namespace>-<CR名>-<uuid>.yaml}。看起来能切，但**命名空间和 CR 名都含 `-`**：
 * <pre>
 *   monitor-ps-kube-prometheus-stack-alertmanager.rules-&lt;uuid&gt;   按第一个 - 切：ns=monitor ✓
 *   my-app-rules-&lt;uuid&gt;                                          按第一个 - 切：ns=my      ✗
 * </pre>
 * 正确做法是**拿平台手上的 (namespace, CR名) 清单去匹配**那些 file —— 永远在一个已知候选集里对，
 * 不存在切错的可能。
 *
 * <p>但 {@code <ns>-<CR名>-} 这个前缀**不是单射**：{@code a}+{@code b-c} 与 {@code a-b}+{@code c}
 * 拼出来都是 {@code a-b-c-}。这种情况下谁都不认领，整份标 {@code unknown} ——
 * 猜错就是把 A 的运行状态挂到 B 身上，比说「对不上账」糟得多。
 *
 * <h2>宽限期</h2>
 * 规则刚建时 Prometheus 还没 reload（prometheus-operator 有延迟），此时"找不到"是**正常**的，
 * 不能报 missing。过了宽限期还找不到，才说明它真的没被拾取 —— 那正是本功能要暴露的那件事。
 */
public final class RuleRuntimeMatcher {

    private RuleRuntimeMatcher() {
    }

    /** 规则文件名的前缀：{@code <ns>-<CR名>-}。public：业务层要按整个列表算歧义（见 {@link #ambiguousPrefixes}）。 */
    public static String filePrefix(PrometheusRuleDTO r) {
        return r.getNamespace() + "-" + r.getName() + "-";
    }

    /**
     * 哪些前缀是**歧义**的（对上多于一份不同 CR）。
     *
     * <p>调用方常常逐条 match（宽限期要按各自的 creationTime 判），那样 {@link #match} 内部的
     * 歧义守卫看不全候选集 —— 所以判断必须**先在整个列表上算一次**，再逐条带上结论。
     * 否则这条守卫在生产路径上是死代码：两份 CR 各自独立匹配，**都会**认领同一个文件。
     */
    public static Set<String> ambiguousPrefixes(List<PrometheusRuleDTO> rules) {
        Map<String, Set<String>> identities = new LinkedHashMap<>();
        for (PrometheusRuleDTO r : rules) {
            if (r.getNamespace() == null || r.getName() == null) {
                continue;
            }
            identities.computeIfAbsent(filePrefix(r), k -> new LinkedHashSet<>())
                    .add(r.getNamespace() + "/" + r.getName());
        }
        Set<String> out = new LinkedHashSet<>();
        identities.forEach((prefix, ids) -> {
            if (ids.size() > 1) {
                out.add(prefix);
            }
        });
        return out;
    }

    /** 一个 {@code loadState=unknown} 的空 runtime（前缀歧义时用）。 */
    public static PrometheusRuleDTO.RuleRuntime unknown() {
        return runtime("unknown", List.of());
    }

    /**
     * @param groups Prometheus 返回的规则组（{@link PromRulesClient#groups}）
     * @param rules  本次 list/get 拿到的 PrometheusRule 对象
     * @param grace  宽限期（未过期就不报 missing）
     * @return 附着到 DTO 上的运行状态；一个都没对齐上且过了宽限期 → {@code missing}
     */
    public static PrometheusRuleDTO.RuleRuntime match(
            List<PromRulesClient.RuleGroup> groups, List<PrometheusRuleDTO> rules, Duration grace) {

        // 候选文件名前缀 → CR。同名 CR 可能在入参里出现多次（调用方按组拼 DTO 的情形），
        // 故歧义判据是 **distinct (ns,name) 的个数**，不是列表长度。
        Map<String, List<PrometheusRuleDTO>> byPrefix = new LinkedHashMap<>();
        for (PrometheusRuleDTO r : rules) {
            if (r.getNamespace() != null && r.getName() != null) {
                byPrefix.computeIfAbsent(filePrefix(r), k -> new ArrayList<>()).add(r);
            }
        }

        // 有任何一个前缀对上多份**不同的** CR → 整份 unknown。宁可说"对不上账"，也不猜。
        if (!ambiguousPrefixes(rules).isEmpty()) {
            return runtime("unknown", List.of());
        }

        // group → 平台认定的 CR（只有前缀匹配上的才算）
        Map<PromRulesClient.RuleGroup, PrometheusRuleDTO> owner = new LinkedHashMap<>();
        for (PromRulesClient.RuleGroup g : groups) {
            PrometheusRuleDTO cr = matchByFilePrefix(g.file(), byPrefix);
            if (cr != null) {
                owner.put(g, cr);
            }
        }

        // 一份都没认领上：要么刚建还没 reload（宽限期内），要么真的没被拾取。
        // 两种情况下逐条运行状态都无从谈起，故 rules 一律为空 —— 不臆造 unknown。
        if (owner.isEmpty()) {
            return runtime(isWithinGrace(rules, grace) ? "loaded" : "missing", List.of());
        }

        // 逐条规则附着：先按 (CR, group名) 建索引，再按规则名取
        Map<String, Map<String, PromRulesClient.RuleView>> index = new LinkedHashMap<>();
        for (Map.Entry<PromRulesClient.RuleGroup, PrometheusRuleDTO> e : owner.entrySet()) {
            PromRulesClient.RuleGroup g = e.getKey();
            String key = crGroupKey(e.getValue(), g.name());
            Map<String, PromRulesClient.RuleView> byName = new LinkedHashMap<>();
            for (PromRulesClient.RuleView v : g.rules()) {
                byName.put(v.name(), v);
            }
            index.merge(key, byName, (a, b) -> {
                a.putAll(b);
                return a;
            });
        }

        List<PrometheusRuleDTO.RuleHealth> out = new ArrayList<>();
        for (PrometheusRuleDTO cr : rules) {
            for (PrometheusRuleDTO.Group g : (cr.getGroups() != null ? cr.getGroups() : List.<PrometheusRuleDTO.Group>of())) {
                Map<String, PromRulesClient.RuleView> byName = index.get(crGroupKey(cr, g.getName()));
                for (PrometheusRuleDTO.Rule r : (g.getRules() != null ? g.getRules() : List.<PrometheusRuleDTO.Rule>of())) {
                    String name = r.getAlert() != null && !r.getAlert().isBlank() ? r.getAlert() : r.getRecord();
                    PromRulesClient.RuleView v = byName != null ? byName.get(name) : null;
                    PrometheusRuleDTO.RuleHealth h = new PrometheusRuleDTO.RuleHealth();
                    h.setGroup(g.getName());
                    h.setName(name);
                    if (v == null) {
                        h.setHealth("unknown");
                    } else {
                        h.setHealth(v.health() != null ? v.health() : "unknown");
                        h.setLastError(v.lastError());
                        h.setState(v.state());
                        h.setActiveAlerts(v.activeAlerts());
                    }
                    out.add(h);
                }
            }
        }
        return runtime("loaded", out);
    }

    private static PrometheusRuleDTO.RuleRuntime runtime(String loadState, List<PrometheusRuleDTO.RuleHealth> rules) {
        PrometheusRuleDTO.RuleRuntime rt = new PrometheusRuleDTO.RuleRuntime();
        rt.setLoadState(loadState);
        rt.setRules(rules);
        return rt;
    }

    /**
     * 在候选集里找前缀匹配的 CR。用 {@code contains("/" + prefix)} 而不是 split —— 见类注释。
     *
     * <p><b>前导斜杠是必须的</b>：file 是绝对路径，前缀前面必有 `/`。去掉它，{@code b-c-}
     * 会匹配到 {@code …/a-b-c-…}。而**歧义**（一个前缀对上多份不同 CR）由调用方在进入本方法前
     * 就已经排除掉了。
     */
    private static PrometheusRuleDTO matchByFilePrefix(String file, Map<String, List<PrometheusRuleDTO>> byPrefix) {
        if (file == null) {
            return null;
        }
        for (Map.Entry<String, List<PrometheusRuleDTO>> e : byPrefix.entrySet()) {
            if (file.contains("/" + e.getKey())) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    /**
     * 宽限期判断：**只要有一条 CR 还在宽限期内**就不报 missing。
     * <p>creationTime 解析不了时按"老对象"处理 —— 宁可报 missing 也别静默放过。
     */
    private static boolean isWithinGrace(List<PrometheusRuleDTO> rules, Duration grace) {
        if (rules.isEmpty()) {
            return true;               // 没有对象可谈，谈不上 missing
        }
        Instant now = Instant.now();
        for (PrometheusRuleDTO r : rules) {
            try {
                Instant created = Instant.parse(r.getCreationTime());
                if (Duration.between(created, now).compareTo(grace) < 0) {
                    return true;
                }
            } catch (Exception ignored) {
                // 解析不了 → 视为老对象，不救
            }
        }
        return false;
    }

    private static String crGroupKey(PrometheusRuleDTO cr, String groupName) {
        return cr.getNamespace() + "/" + cr.getName() + "#" + groupName;
    }

}
