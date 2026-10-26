package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserView;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis（opstree）只支持**改口令**：密码在 Secret {@code <实例名>-credentials} 的 {@code password} 键，
 * 改完滚动重启一次让进程重新读配置。
 *
 * <p>opstree 不管理 ACL 用户，所以 {@code create}/{@code drop} 一律拒绝（服务层也挡了一次）。
 * 若真集群验证发现 operator 会把 Secret 回写成旧值，本适配器要从支持清单里去掉。
 */
@Component
public class RedisUserAdapter implements MiddlewareUserAdapter {

    static final String BUILTIN = "default";
    static final String CREDENTIAL_SECRET_SUFFIX = "-credentials";
    static final String INSTANCE_LABEL = "platform.coding/middleware-instance";

    @Override
    public String code() {
        return "redis";
    }

    @Override
    public List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref) {
        MiddlewareUserView builtin = builtinUsers().get(0);
        builtin.setSecretName(credentialsSecretName(ref.instanceName()));
        return List.of(builtin);
    }

    @Override
    public String create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role) {
        throw new CloudPlatformException(EnumResponseType.ERROR, "Redis 只支持修改口令，不支持新增用户");
    }

    @Override
    public void drop(KubernetesClient client, MiddlewareUserRef ref, String username) {
        throw new CloudPlatformException(EnumResponseType.ERROR, "Redis 只支持修改口令，不支持删除用户");
    }

    @Override
    public String changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword) {
        if (!BUILTIN.equals(username)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Redis 只有内置账号 " + BUILTIN + "，没有账号：" + username);
        }
        String secretName = credentialsSecretName(ref.instanceName());
        Secret current = client.secrets().inNamespace(ref.namespace()).withName(secretName).get();
        if (current == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "找不到凭据 Secret：" + secretName);
        }
        Map<String, String> data = current.getData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(current.getData());
        data.put("password", Base64.getEncoder()
                .encodeToString(newPassword.getBytes(StandardCharsets.UTF_8)));
        client.secrets().inNamespace(ref.namespace())
                .resource(new SecretBuilder(current).withData(data).build()).createOrReplace();
        restartWorkloads(client, ref);
        return newPassword;
    }

    static List<MiddlewareUserView> builtinUsers() {
        MiddlewareUserView v = new MiddlewareUserView();
        v.setUsername(BUILTIN);
        v.setRole(null);
        v.setBuiltin(true);
        v.setSecretKey("password");
        return List.of(v);
    }

    static String credentialsSecretName(String instanceName) {
        return instanceName + CREDENTIAL_SECRET_SUFFIX;
    }

    /**
     * 让进程重新读 Secret：先按平台标签找，再兜底按名字前缀找（老实例可能没打标签）。
     */
    private static void restartWorkloads(KubernetesClient client, MiddlewareUserRef ref) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (ref.instanceId() != null && !ref.instanceId().isBlank()) {
            client.apps().statefulSets().inNamespace(ref.namespace())
                    .withLabel(INSTANCE_LABEL, ref.instanceId()).list().getItems()
                    .forEach(s -> names.add(s.getMetadata().getName()));
        }
        client.apps().statefulSets().inNamespace(ref.namespace()).list().getItems().stream()
                .map(s -> s.getMetadata().getName())
                .filter(n -> n != null && n.startsWith(ref.instanceName()))
                .forEach(names::add);
        names.forEach(n -> client.apps().statefulSets().inNamespace(ref.namespace())
                .withName(n).rolling().restart());
    }
}
