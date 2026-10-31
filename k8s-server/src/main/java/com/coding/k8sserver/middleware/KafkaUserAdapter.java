package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserView;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kafka（Strimzi）的用户管理：用户 = {@code kafkausers.kafka.strimzi.io} CR（SCRAM-SHA-512）。
 *
 * <p>operator 会为用户生成同名 Secret（含 {@code password}），所以"建用户"之后要等它出现；
 * 改口令 = 删 CR 再建（Strimzi 只在创建时生成 SCRAM 凭据）。
 *
 * <p><b>本批不开授权</b>：所有认证用户全权，角色只有一个 {@code client}。
 */
@Component
public class KafkaUserAdapter implements MiddlewareUserAdapter {

    static final String GROUP = "kafka.strimzi.io";
    static final String VERSION = "v1beta2";
    static final String CLUSTER_LABEL = "strimzi.io/cluster";
    static final List<String> ROLES = List.of("client");

    static final CustomResourceDefinitionContext USER_CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION)
            .withPlural("kafkausers").withKind("KafkaUser").withScope("Namespaced")
            .build();

    static final CustomResourceDefinitionContext KAFKA_CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION)
            .withPlural("kafkas").withKind("Kafka").withScope("Namespaced")
            .build();

    /** 等 operator 生成用户 Secret 的上限（秒） */
    private static final int SECRET_WAIT_SECONDS = 10;

    @Override
    public String code() {
        return "kafka";
    }

    @Override
    public List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref) {
        Map<String, String> platformUsers = UsersSecret.passwords(
                UsersSecret.read(client, ref.namespace(), ref.instanceName()));

        List<MiddlewareUserView> out = new ArrayList<>();
        for (GenericKubernetesResource cr : client.genericKubernetesResources(USER_CRD)
                .inNamespace(ref.namespace()).withLabel(CLUSTER_LABEL, ref.instanceName())
                .list().getItems()) {
            String name = cr.getMetadata().getName();
            if (!platformUsers.containsKey(name)) {
                continue;   // 运维手工建的 KafkaUser 不接管（也不能删）
            }
            out.add(view(name, ref));
        }
        return out;
    }

    @Override
    public String create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role) {
        requireScramListener(client, ref);
        requireUserAbsent(client, ref, username);
        client.genericKubernetesResources(USER_CRD).inNamespace(ref.namespace())
                .resource(userCr(username, ref.instanceName()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        // 口令由 operator 生成（平台传入的被忽略），必须回传真实的那个，否则写进 -users 的是错的
        return awaitSecret(client, ref, username);
    }

    /**
     * 「新增用户」必须真的新建。
     *
     * <p>建 CR 走 SSA 之后它是 upsert —— 重名不会报错，而是把同名 KafkaUser 覆盖掉，
     * 于是「加一个已存在的用户」变成「悄悄重置他的 SCRAM 凭据」并且返回成功。
     * 所以新增路径自己先判存在；{@link #changePassword} 那边靠「删完等它真的没了」保证，无需再查。
     */
    private static void requireUserAbsent(KubernetesClient client, MiddlewareUserRef ref, String username) {
        if (client.genericKubernetesResources(USER_CRD).inNamespace(ref.namespace())
                .withName(username).get() != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST, "用户已存在：" + username);
        }
    }

    @Override
    public void drop(KubernetesClient client, MiddlewareUserRef ref, String username) {
        client.genericKubernetesResources(USER_CRD).inNamespace(ref.namespace())
                .withName(username).delete();
    }

    @Override
    public String changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword) {
        // Strimzi 只在创建时生成 SCRAM 凭据：删了重建就是"重置口令"（新口令也是 operator 生成）
        requireScramListener(client, ref);
        client.genericKubernetesResources(USER_CRD).inNamespace(ref.namespace())
                .withName(username).delete();
        awaitGone(client, ref, username);
        client.genericKubernetesResources(USER_CRD).inNamespace(ref.namespace())
                .resource(userCr(username, ref.instanceName()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return awaitSecret(client, ref, username);
    }

    @Override
    public List<String> roles(KubernetesClient client, MiddlewareUserRef ref) {
        return ROLES;
    }

    // ---------- 纯函数 ----------

    static GenericKubernetesResource userCr(String username, String cluster) {
        GenericKubernetesResource cr = new GenericKubernetesResource();
        cr.setApiVersion(GROUP + "/" + VERSION);
        cr.setKind("KafkaUser");
        ObjectMeta meta = new ObjectMeta();
        meta.setName(username);
        meta.setLabels(Map.of(CLUSTER_LABEL, cluster));
        cr.setMetadata(meta);
        cr.setAdditionalProperties(Map.of(
                "spec", Map.of("authentication", Map.of("type", "scram-sha-512"))));
        return cr;
    }

    static MiddlewareUserView view(String username, MiddlewareUserRef ref) {
        MiddlewareUserView v = new MiddlewareUserView();
        v.setUsername(username);
        v.setRole("client");
        v.setBuiltin(false);
        v.setSecretName(username);
        v.setSecretKey("password");
        return v;
    }

    // ---------- 内部 ----------

    /**
     * 存量实例可能还没有 {@code scram} listener（本批之前的 CR 只有 plain）—— 那种情况下
     * operator 仍会生成凭据，但 9093 根本不存在，用户拿到的口令连不上。这里直接拒绝，别产出"假的可用"。
     */
    private static void requireScramListener(KubernetesClient client, MiddlewareUserRef ref) {
        GenericKubernetesResource kafka = client.genericKubernetesResources(KAFKA_CRD)
                .inNamespace(ref.namespace()).withName(ref.instanceName()).get();
        if (kafka != null && hasScramListener(kafka)) {
            return;
        }
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "该 Kafka 实例还没有 SASL listener（scram:9093）：请在组件页重放一次实例，再管理用户");
    }

    /** 纯函数：CR 里是否有一条 listener 用 scram-sha-512 认证 */
    static boolean hasScramListener(GenericKubernetesResource kafka) {
        Object spec = kafka.getAdditionalProperties() == null ? null : kafka.getAdditionalProperties().get("spec");
        if (!(spec instanceof Map<?, ?> specMap) || !(specMap.get("kafka") instanceof Map<?, ?> kafkaMap)) {
            return false;
        }
        if (!(kafkaMap.get("listeners") instanceof List<?> listeners)) {
            return false;
        }
        for (Object item : listeners) {
            if (item instanceof Map<?, ?> listener
                    && listener.get("authentication") instanceof Map<?, ?> auth
                    && "scram-sha-512".equals(auth.get("type"))) {
                return true;
            }
        }
        return false;
    }

    /** 等 operator 生成凭据 Secret，返回里面的明文口令。 */
    private static String awaitSecret(KubernetesClient client, MiddlewareUserRef ref, String username) {
        for (int i = 0; i < SECRET_WAIT_SECONDS; i++) {
            var secret = client.secrets().inNamespace(ref.namespace()).withName(username).get();
            if (secret != null && secret.getData() != null && secret.getData().get("password") != null) {
                return new String(java.util.Base64.getDecoder()
                        .decode(secret.getData().get("password")), java.nio.charset.StandardCharsets.UTF_8);
            }
            sleep();
        }
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "账号 " + username + " 已创建，但 operator 还没生成凭据 Secret（稍后到 Secret " + username
                        + " 的 password 键查看）");
    }

    private static void awaitGone(KubernetesClient client, MiddlewareUserRef ref, String username) {
        for (int i = 0; i < SECRET_WAIT_SECONDS; i++) {
            if (client.genericKubernetesResources(USER_CRD).inNamespace(ref.namespace())
                    .withName(username).get() == null) {
                return;
            }
            sleep();
        }
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "重置口令失败：旧账号 " + username + " 没有被删除（可能仍有 topic 权限绑定）");
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
