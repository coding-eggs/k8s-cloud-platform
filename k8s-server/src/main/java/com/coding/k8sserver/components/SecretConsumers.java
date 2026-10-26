package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeProjection;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.utils.Serialization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让"会读某个配置 Secret"的工作负载重新读一次（S3.6，2026-10-26）。
 *
 * <p><b>为什么需要</b>：平台把对象存储配置写成 Secret（{@code platform-thanos-objstore}），
 * 而不是走 helm values。chart 上的 {@code checksum/objstore-configuration} 注解算的是
 * {@code global.objstore.config}（平台从没设过，恒为默认值），所以改 S3 地址后
 * <b>helm upgrade 不会动 Pod 模板</b>；而 Thanos 这类进程只在启动时读
 * {@code --objstore.config-file}，kubelet 把挂载的文件更新了也不生效 —— 表现就是
 * "改了地址没生效"，compactor/receive 还在往旧桶写（2026-10-26 用户实报，见 thanos README §6）。
 *
 * <p><b>怎么做</b>：给这些工作负载的 Pod 模板打一个"我读到的配置哈希"注解。注解值与新哈希不一致
 * 就说明它跑的是旧配置 —— 改写注解既记录了新哈希，也换掉了 Pod 模板，滚动随之发生；
 * 一致就什么都不做。所以重复重放是幂等的，而"平台上线这个修复之前就残留的旧 Pod"
 * （没有注解）也会在下一次重放时被自动纠一次，不需要人工 {@code rollout restart}。
 *
 * <p>判据是"Pod 模板里有没有引用这个 Secret"（卷、projected 卷、envFrom、env.valueFrom），
 * 而不是按名字猜：这样 query 这种不读对象存储的组件不会被无谓重启。
 * 只覆盖 StatefulSet 与 Deployment；collector 的 Pod 是 operator 从 CR 生成的，走
 * {@link #alignCollector}（改 CR 的 {@code spec.podAnnotations}，见 otel-operator README §7 第 12 条）。
 *
 * <p>用在三处：Thanos 对象存储（改地址/桶/凭证）、Tempo 凭证、collector 的 ES 凭证。
 */
public final class SecretConsumers {

    /**
     * Pod 模板上的"已对齐配置哈希"注解，值形如 {@code platform-thanos-objstore:3f2a…}。
     * 模板里带着它 = 这批 Pod 是按这份 Secret 内容启动的。
     */
    public static final String CONFIG_HASH_ANNOTATION = "platform.coding/config-hash";

    /** OTel collector 的 CR（v1beta1）；Pod 由 operator 从它生成。 */
    private static final CustomResourceDefinitionContext OTEL_COLLECTOR_CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("opentelemetry.io")
                    .withVersion("v1beta1")
                    .withKind("OpenTelemetryCollector")
                    .withPlural("opentelemetrycollectors")
                    .withScope("Namespaced")
                    .build();

    private SecretConsumers() {
    }

    /** 配置内容的哈希（十六进制 sha256）：用来判断"跑的是不是同一份配置"。 */
    public static String hashOf(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算配置哈希失败", e);
        }
    }

    /**
     * 键值形式的配置（如凭证）→ 哈希。按 key 排序，同一个 Secret 无论 map 顺序都得到同一个值。
     */
    public static String hashOf(Map<String, String> values) {
        StringBuilder canonical = new StringBuilder();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> canonical.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        return hashOf(canonical.toString());
    }

    /** Pod 模板记录的哈希与期望值不一致（含"根本没记录"）→ 需要滚动。 */
    public static boolean needsAlign(Map<String, String> templateAnnotations, String expected) {
        return templateAnnotations == null || !expected.equals(templateAnnotations.get(CONFIG_HASH_ANNOTATION));
    }

    /**
     * 把命名空间内所有引用该 Secret 的 StatefulSet / Deployment 对齐到 {@code contentHash}：
     * 模板注解不一致的改写注解（触发滚动），一致的跳过。
     *
     * @return 被滚动的对象（{@code statefulset/xxx}、{@code deployment/xxx}），供日志与测试断言
     */
    public static List<String> alignConsumers(KubernetesClient client, String namespace,
                                              String secretName, String contentHash) {
        String expected = secretName + ":" + contentHash;
        List<String> rolled = new ArrayList<>();
        for (StatefulSet sts : client.apps().statefulSets().inNamespace(namespace).list().getItems()) {
            String name = sts.getMetadata() == null ? null : sts.getMetadata().getName();
            if (name == null || !usesSecret(sts.getSpec(), secretName)
                    || !needsAlign(templateAnnotations(sts.getSpec()), expected)) {
                continue;
            }
            client.apps().statefulSets().inNamespace(namespace).withName(name).edit(s -> new StatefulSetBuilder(s)
                    .editSpec().editTemplate().editMetadata()
                    .addToAnnotations(CONFIG_HASH_ANNOTATION, expected)
                    .endMetadata().endTemplate().endSpec().build());
            rolled.add("statefulset/" + name);
        }
        for (Deployment dep : client.apps().deployments().inNamespace(namespace).list().getItems()) {
            String name = dep.getMetadata() == null ? null : dep.getMetadata().getName();
            if (name == null || !usesSecret(dep.getSpec(), secretName)
                    || !needsAlign(templateAnnotations(dep.getSpec()), expected)) {
                continue;
            }
            client.apps().deployments().inNamespace(namespace).withName(name).edit(d -> new DeploymentBuilder(d)
                    .editSpec().editTemplate().editMetadata()
                    .addToAnnotations(CONFIG_HASH_ANNOTATION, expected)
                    .endMetadata().endTemplate().endSpec().build());
            rolled.add("deployment/" + name);
        }
        return rolled;
    }

    /**
     * OTel collector 的同类对齐：它的 Pod 由 **operator 从 CR 生成**，平台不能给那个 Deployment 打注解
     * （下一次 reconcile 会被改回去），正确的手柄是 CR 的 {@code spec.podAnnotations} —— 改它
     * operator 会滚动自己生成的工作负载。
     *
     * <p>CRD / CR 不存在（没装 otel-operator 或没开日志）时静默返回 false：这是"没有消费方"，
     * 不是失败。
     */
    public static boolean alignCollector(KubernetesClient client, String namespace, String collectorName,
                                         String secretName, String contentHash) {
        if (collectorName == null || collectorName.isBlank()) {
            return false;
        }
        String expected = secretName + ":" + contentHash;
        GenericKubernetesResource collector;
        try {
            collector = client.genericKubernetesResources(OTEL_COLLECTOR_CRD)
                    .inNamespace(namespace).withName(collectorName).get();
        } catch (Exception e) {
            return false; // 没有这个 CRD
        }
        if (collector == null) {
            return false;
        }
        Map<String, Object> annotations = asMap(
                asMap(collector.getAdditionalProperties().get("spec")).get("podAnnotations"));
        if (expected.equals(annotations.get(CONFIG_HASH_ANNOTATION))) {
            return false;
        }
        annotations.put(CONFIG_HASH_ANNOTATION, expected);
        // JSON merge patch 只合并给出的子键，不会把 spec 里别的东西（env、config…）冲掉
        Map<String, Object> patch = Map.of("spec", Map.of("podAnnotations", annotations));
        client.genericKubernetesResources(OTEL_COLLECTOR_CRD).inNamespace(namespace).withName(collectorName)
                .patch(PatchContext.of(PatchType.JSON_MERGE), Serialization.asJson(patch));
        return true;
    }

    /** Pod 模板是否引用该 Secret（卷 / projected 卷 / envFrom / env.valueFrom）。 */
    public static boolean podSpecUsesSecret(PodSpec spec, String secretName) {

        if (spec == null || secretName == null || secretName.isEmpty()) {
            return false;
        }
        if (spec.getVolumes() != null) {
            for (Volume volume : spec.getVolumes()) {
                if (volume == null) {
                    continue;
                }
                if (volume.getSecret() != null && secretName.equals(volume.getSecret().getSecretName())) {
                    return true;
                }
                if (volume.getProjected() != null && volume.getProjected().getSources() != null) {
                    for (VolumeProjection projection : volume.getProjected().getSources()) {
                        if (projection != null && projection.getSecret() != null
                                && secretName.equals(projection.getSecret().getName())) {
                            return true;
                        }
                    }
                }
            }
        }
        return containersUseSecret(spec.getInitContainers(), secretName)
                || containersUseSecret(spec.getContainers(), secretName);
    }

    private static boolean containersUseSecret(List<Container> containers, String secretName) {
        if (containers == null) {
            return false;
        }
        for (Container container : containers) {
            if (container == null) {
                continue;
            }
            if (container.getEnvFrom() != null) {
                for (EnvFromSource from : container.getEnvFrom()) {
                    if (from != null && from.getSecretRef() != null
                            && secretName.equals(from.getSecretRef().getName())) {
                        return true;
                    }
                }
            }
            if (container.getEnv() != null) {
                for (EnvVar env : container.getEnv()) {
                    if (env != null && env.getValueFrom() != null && env.getValueFrom().getSecretKeyRef() != null
                            && secretName.equals(env.getValueFrom().getSecretKeyRef().getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean usesSecret(StatefulSetSpec spec, String secretName) {
        return spec != null && spec.getTemplate() != null
                && podSpecUsesSecret(spec.getTemplate().getSpec(), secretName);
    }

    private static boolean usesSecret(DeploymentSpec spec, String secretName) {
        return spec != null && spec.getTemplate() != null
                && podSpecUsesSecret(spec.getTemplate().getSpec(), secretName);
    }

    private static Map<String, String> templateAnnotations(StatefulSetSpec spec) {
        return spec.getTemplate().getMetadata() == null ? null : spec.getTemplate().getMetadata().getAnnotations();
    }

    private static Map<String, String> templateAnnotations(DeploymentSpec spec) {
        return spec.getTemplate().getMetadata() == null ? null : spec.getTemplate().getMetadata().getAnnotations();
    }

    /** 泛型资源里的 Map 一律是 {@code Map<String,Object>}，取出来先拷一份再改（别改到客户端缓存对象）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
    }
}
