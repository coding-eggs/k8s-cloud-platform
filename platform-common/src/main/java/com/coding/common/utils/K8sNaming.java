package com.coding.common.utils;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;

import java.util.regex.Pattern;

/**
 * 平台在 K8s 侧创建对象的命名规范与校验。
 * <p>
 * 统一前缀约定（便于与集群自身组件区分、租户删除时批量清理）：
 * <ul>
 *   <li>租户 ServiceAccount / RoleBinding 名：{@code tn-<service_account>}</li>
 *   <li>Rbac 模板 ClusterRole 名：{@code tn-tpl-<模板名>}</li>
 * </ul>
 * 库内存裸名（platform_tenant.service_account、platform_rbac_template.name），
 * 实际 K8s 对象名 = 前缀 + 裸名。所有名称必须满足 RFC1123，且加前缀后总长 ≤ 63。
 */
public final class K8sNaming {

    /** 租户 SA / RoleBinding 名前缀 */
    public static final String TENANT_SA_PREFIX = "tn-";

    /** RBAC 模板 ClusterRole 名前缀 */
    public static final String TEMPLATE_ROLE_PREFIX = "tn-tpl-";

    /** K8s 对象名长度上限 */
    public static final int MAX_NAME_LENGTH = 63;

    private static final Pattern RFC1123 = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private K8sNaming() {
    }

    /**
     * 租户 ServiceAccount 名（同命名空间内 RoleBinding 复用此名）
     */
    public static String tenantServiceAccount(String serviceAccount) {
        return TENANT_SA_PREFIX + serviceAccount;
    }

    /**
     * RBAC 模板对应的 ClusterRole 名
     */
    public static String templateClusterRole(String templateName) {
        return TEMPLATE_ROLE_PREFIX + templateName;
    }

    /**
     * 校验裸名（未加前缀）是否合法：RFC1123 且加前缀后总长 ≤ 63。
     *
     * @param name      裸名
     * @param prefix    将拼接的前缀
     * @param fieldName 出错提示用的字段名
     */
    public static void validateRawName(String name, String prefix, String fieldName) {
        if (name == null || !RFC1123.matcher(name).matches()) {
            throw new CloudPlatformException(EnumResponseType.INVALID_K8S_NAME,
                    EnumResponseType.INVALID_K8S_NAME.getMsg() + fieldName + "=" + name
                            + "（要求：小写字母/数字/-，首尾不能为 -）");
        }
        int max = MAX_NAME_LENGTH - prefix.length();
        if (name.length() > max) {
            throw new CloudPlatformException(EnumResponseType.INVALID_K8S_NAME,
                    EnumResponseType.INVALID_K8S_NAME.getMsg() + fieldName + " 长度超过 " + max
                            + "（加前缀 " + prefix + " 后不得超过 " + MAX_NAME_LENGTH + "）");
        }
    }

}
