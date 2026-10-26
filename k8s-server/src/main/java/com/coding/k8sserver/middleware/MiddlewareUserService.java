package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserCredentialDTO;
import com.coding.common.models.middleware.MiddlewareUserDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserListRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserPasswordRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserRules;
import com.coding.common.models.middleware.MiddlewareUserView;
import com.coding.k8score.factory.KubernetesClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理的执行层：按 code 分派到各家的 {@link MiddlewareUserAdapter}。
 *
 * <p><b>身份</b>：一律 admin client —— 租户 SA 的 RBAC 模板里没有 {@code pods/exec}。
 * 授权在 platform-api 层做（实例归属 + 分配表），这里不再判。
 *
 * <p><b>口令去向</b>：平台建的账号写 Secret {@code <实例名>-users}（key = 用户名）；
 * 内置账号由适配器写回各自的凭据 Secret，**不进** {@code -users} ——
 * 否则"平台建的账号 = -users 里有 key"这条识别规则会把 root 也算进去。
 */
@Service
public class MiddlewareUserService {

    private final Map<String, MiddlewareUserAdapter> adapters;
    private final KubernetesClientFactory clients;

    public MiddlewareUserService(List<MiddlewareUserAdapter> adapters, KubernetesClientFactory clients) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(MiddlewareUserAdapter::code, Function.identity(), (a, b) -> a));
        this.clients = clients;
    }

    public List<MiddlewareUserView> list(String code, MiddlewareUserListRequestDTO req) {
        MiddlewareUserAdapter adapter = adapterFor(code);
        return adapter.list(client(req.getClusterId()), ref(req.getName(), req.getNamespace(), req.getInstanceId()));
    }

    public MiddlewareUserCredentialDTO create(String code, MiddlewareUserCreateRequestDTO req) {
        MiddlewareUserAdapter adapter = adapterFor(code);
        requireCreatable(code);
        MiddlewareUserRules.requireUsername(req.getUsername());
        MiddlewareUserRules.requirePasswordLength(req.getPassword());

        KubernetesClient client = client(req.getClusterId());
        adapter.create(client, ref(req.getName(), req.getNamespace(), req.getInstanceId()),
                req.getUsername(), req.getPassword(), req.getRole());
        // 账号已经建出来了：Secret 写不进去只能显式报错让人工补，不回滚账号
        try {
            UsersSecret.put(client, req.getNamespace(), req.getName(), req.getUsername(), req.getPassword());
        } catch (RuntimeException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "账号已创建，但 Secret " + UsersSecret.name(req.getName()) + " 未更新：" + e.getMessage());
        }
        return credential(req.getName(), req.getUsername(), req.getPassword());
    }

    public MiddlewareUserCredentialDTO changePassword(String code, MiddlewareUserPasswordRequestDTO req) {
        MiddlewareUserAdapter adapter = adapterFor(code);
        requirePasswordChangeable(code);
        MiddlewareUserRules.requirePasswordLength(req.getNewPassword());

        KubernetesClient client = client(req.getClusterId());
        MiddlewareUserRef ref = ref(req.getName(), req.getNamespace(), req.getInstanceId());
        adapter.changePassword(client, ref, req.getUsername(), req.getNewPassword());
        // 只有平台建的账号才进 -users；内置账号的口令由适配器写回各家自己的 Secret
        if (UsersSecret.passwords(UsersSecret.read(client, req.getNamespace(), req.getName()))
                .containsKey(req.getUsername())) {
            try {
                UsersSecret.put(client, req.getNamespace(), req.getName(), req.getUsername(), req.getNewPassword());
            } catch (RuntimeException e) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "口令已生效，但 Secret " + UsersSecret.name(req.getName()) + " 未更新：" + e.getMessage());
            }
        }
        return credential(req.getName(), req.getUsername(), req.getNewPassword());
    }

    public void drop(String code, MiddlewareUserDeleteRequestDTO req) {
        MiddlewareUserAdapter adapter = adapterFor(code);
        requireDeletable(code);
        KubernetesClient client = client(req.getClusterId());
        adapter.drop(client, ref(req.getName(), req.getNamespace(), req.getInstanceId()), req.getUsername());
        UsersSecret.remove(client, req.getNamespace(), req.getName(), req.getUsername());
    }

    // ---------- 分派与规则 ----------

    MiddlewareUserAdapter adapterFor(String code) {
        MiddlewareUserAdapter adapter = code == null ? null : adapters.get(code);
        if (adapter == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "该中间件暂不支持用户管理");
        }
        return adapter;
    }

    void requireCreatable(String code) {
        if (isRedis(code)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Redis 不支持新增用户（opstree 不管理 ACL 用户），只支持修改口令");
        }
    }

    void requireDeletable(String code) {
        if (isRedis(code)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Redis 不支持删除用户，只支持修改口令");
        }
    }

    /** 目前各家都能改口令；留这个方法是为了 Kafka/Nacos 将来若关掉鉴权时有统一落点 */
    void requirePasswordChangeable(String code) {
        if (code == null || code.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少中间件类型");
        }
    }

    private static boolean isRedis(String code) {
        return "redis".equals(code);
    }

    private KubernetesClient client(String clusterId) {
        if (clusterId == null || clusterId.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请选择集群");
        }
        return clients.getAdminClient(clusterId);
    }

    private static MiddlewareUserRef ref(String name, String namespace, String instanceId) {
        return new MiddlewareUserRef(name, namespace, instanceId);
    }

    private static MiddlewareUserCredentialDTO credential(String instanceName, String username, String password) {
        MiddlewareUserCredentialDTO dto = new MiddlewareUserCredentialDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setSecretName(UsersSecret.name(instanceName));
        dto.setSecretKey(username);
        return dto;
    }
}
