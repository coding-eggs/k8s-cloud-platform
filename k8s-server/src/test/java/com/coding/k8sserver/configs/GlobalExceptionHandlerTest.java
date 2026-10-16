package com.coding.k8sserver.configs;

import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集群侧 403 必须变成一句能自解释、能直接转给管理员的话。
 *
 * <p>这些异常原先落进 {@code handleOtherError}，返回 code=5000 / msg="服务端异常"，前端只能显示这四个字。
 * 2026-10-17 报的「租户身份打开任何 CRD 页都失败」就是这个形态 —— 缺的规则名、apiGroup、SA 全在
 * {@link KubernetesClientException#getMessage()} 里，但一个字都没透出来，用户与运维都无从下手。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 真实的 403 原文形态（kubectl/apiserver 就是这么写的），照抄一份当输入。 */
    private static KubernetesClientException forbidden(String group, String resource) {
        return new KubernetesClientException(
                resource + "." + group + " is forbidden: User "
                        + "\"system:serviceaccount:platform-system:tn-default\" cannot list resource \""
                        + resource + "\" in API group \"" + group + "\" in the namespace \"default\"",
                403, null);
    }

    @Test
    void forbiddenFromClusterBecomesAnActionableRbacMessage() {
        ResponseData<String> resp = handler.handleKubernetesClientException(
                forbidden("monitoring.coreos.com", "servicemonitors"));

        assertThat(resp.getCode()).isEqualTo(EnumResponseType.K8S_RBAC_FORBIDDEN.getCode());
        // 集群原文要带上：apiGroup / resource / SA 全在里面，正是排障要看的那几个词
        assertThat(resp.getMsg())
                .contains("RBAC 模板")
                .contains("monitoring.coreos.com")
                .contains("servicemonitors");
    }

    /**
     * 只翻译 403。其它码（404/409/422…）维持原样 —— 它们不是权限问题，
     * 硬套一句「检查 RBAC 模板」会把排障引到错的方向。
     */
    @Test
    void otherClusterErrorsKeepTheGenericBehaviour() {
        ResponseData<String> resp = handler.handleKubernetesClientException(
                new KubernetesClientException("boom", 404, null));

        assertThat(resp.getCode()).isEqualTo(EnumResponseType.ERROR.getCode());
        assertThat(resp.getMsg()).isEqualTo(EnumResponseType.ERROR.getMsg());
    }
}
