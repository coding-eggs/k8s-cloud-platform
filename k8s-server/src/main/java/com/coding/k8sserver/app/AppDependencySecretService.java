package com.coding.k8sserver.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppDependencySecretRequestDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨命名空间依赖的凭据副本：把实例的凭据 Secret 复制到应用命名空间。
 *
 * <p>为什么需要复制：K8s 的 {@code secretKeyRef} 不能跨命名空间，而实例可能建在租户的另一个
 * 命名空间里。复制由 platform-api 在发布 / 初始化前触发（它知道依赖名，决定副本名）。
 *
 * <p>只做事实与边界：读来源、落一份带平台标签的副本、清掉本应用不再需要的副本。副本名、命名与
 * 归属标签的约定都在 platform-api 一侧，这里不重复推算。
 */
@Slf4j
@Service
public class AppDependencySecretService {

    /** 副本的归属标签：清理只认这一组，用户自建的 Secret 不会被删，也不会被覆盖。 */
    static final String MANAGED_BY_LABEL = "platform.coding/managed-by";
    static final String MANAGED_BY_VALUE = "app-dependency-secret";
    static final String APP_ID_LABEL = "platform.coding/app-id";
    /** 记来源，排查时能看出这份副本是哪个 ns / Secret 抄来的。 */
    static final String SOURCE_ANNOTATION = "platform.coding/dependency-source";

    private final KubernetesClientFactory clients;

    public AppDependencySecretService(KubernetesClientFactory clients) {
        this.clients = clients;
    }

    public void sync(AppDependencySecretRequestDTO req) {
        if (isBlank(req.getClusterId()) || isBlank(req.getNamespace()) || isBlank(req.getAppId())) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "依赖 Secret 同步参数不完整");
        }
        List<AppDependencySecretRequestDTO.Copy> copies = req.getCopies() == null
                ? List.of() : req.getCopies();
        KubernetesClient client = clients.getAdminClient(req.getClusterId());
        Set<String> wanted = new LinkedHashSet<>();
        for (AppDependencySecretRequestDTO.Copy copy : copies) {
            if (isBlank(copy.getSourceNamespace()) || isBlank(copy.getSourceName())
                    || isBlank(copy.getTargetName())) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "依赖 Secret 同步参数不完整");
            }
            wanted.add(copy.getTargetName());
            copyOne(client, req.getNamespace(), req.getAppId(), copy);
        }
        prune(client, req.getNamespace(), req.getAppId(), wanted);
    }

    private void copyOne(KubernetesClient client, String namespace, String appId,
                         AppDependencySecretRequestDTO.Copy copy) {
        String source = copy.getSourceNamespace() + "/" + copy.getSourceName();
        Secret from = client.secrets().inNamespace(copy.getSourceNamespace())
                .withName(copy.getSourceName()).get();
        if (from == null) {
            if (copy.isRequired()) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "依赖的凭据 Secret 不存在：" + source);
            }
            // 只是登记着、这次不用的依赖（例如实例还在创建、凭据 Secret 还没生成）：不挡发布，
            // 已有的副本也保留（在 keep 集合里），等来源可用后的下一次发布再同步
            log.warn("依赖凭据 Secret 还不存在，跳过副本 {} ← {}", copy.getTargetName(), source);
            return;
        }
        Secret existing = client.secrets().inNamespace(namespace).withName(copy.getTargetName()).get();
        if (existing != null && !ours(existing, appId)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "应用命名空间里已有同名的 Secret " + copy.getTargetName() + "，不是平台复制的，不能覆盖");
        }
        Secret target = new SecretBuilder()
                .withNewMetadata()
                .withName(copy.getTargetName())
                .withNamespace(namespace)
                .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .addToLabels(APP_ID_LABEL, appId)
                .addToAnnotations(SOURCE_ANNOTATION, source)
                .endMetadata()
                .withType(from.getType())
                .withData(from.getData() == null ? Map.of() : from.getData())
                .build();
        client.secrets().inNamespace(namespace).resource(target)
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
    }

    /** 本应用复制出来、但这次不在清单里的副本删掉：移除依赖 / 换绑实例后不留孤儿。 */
    private void prune(KubernetesClient client, String namespace, String appId, Set<String> keep) {
        List<Secret> existing = client.secrets().inNamespace(namespace)
                .withLabels(Map.of(MANAGED_BY_LABEL, MANAGED_BY_VALUE, APP_ID_LABEL, appId))
                .list().getItems();
        for (Secret secret : existing) {
            String name = secret.getMetadata() == null ? null : secret.getMetadata().getName();
            if (name == null || keep.contains(name)) {
                continue;
            }
            client.secrets().inNamespace(namespace).withName(name).delete();
        }
    }

    private static boolean ours(Secret secret, String appId) {
        Map<String, String> labels = secret.getMetadata() == null ? null : secret.getMetadata().getLabels();
        return labels != null && MANAGED_BY_VALUE.equals(labels.get(MANAGED_BY_LABEL))
                && appId.equals(labels.get(APP_ID_LABEL));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
