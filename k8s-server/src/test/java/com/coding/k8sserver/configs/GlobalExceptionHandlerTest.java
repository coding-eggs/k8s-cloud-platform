package com.coding.k8sserver.configs;

import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import io.fabric8.kubernetes.api.model.StatusBuilder;
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
     * 非 403 也要把集群的原话交回去。
     *
     * <p>以前这类异常整条交给 {@code handleOtherError}，响应 msg 只剩「服务端异常」——
     * 2026-10-31「更新 Service 的端口」就撞在这个形态上（apiserver 说
     * {@code duplicate nodePort: {TCP 25358}}，界面一个字都没透出来，只能翻服务端日志）。
     *
     * <p>也不套 RBAC 措辞：404/409/422/500 的成因五花八门，硬说「检查 RBAC 模板」会把排障引到错的方向。
     */
    @Test
    void otherClusterErrorsSurfaceTheApiserverMessage() {
        var status = new StatusBuilder().withMessage("duplicate nodePort: {TCP 25358}").build();
        var e = new KubernetesClientException(
                "Failure executing: PATCH at: https://10.0.0.1:6443/api/v1/namespaces/platform-system/services/x",
                500, status);

        ResponseData<String> resp = handler.handleKubernetesClientException(e);

        assertThat(resp.getCode()).isEqualTo(EnumResponseType.ERROR.getCode());
        // 取的是 status.message（一句原因），不是整段 "Failure executing: PATCH at: ..."
        assertThat(resp.getMsg())
                .contains("duplicate nodePort: {TCP 25358}")
                .doesNotContain("Failure executing")
                // 不能被误标成权限问题
                .doesNotContain("RBAC");
    }

    /** 没有 status 的构造（客户端侧异常）：退回 e.getMessage()，同样不能回到「服务端异常」。 */
    @Test
    void clusterErrorsWithoutAStatusStillCarryTheirOwnMessage() {
        ResponseData<String> resp = handler.handleKubernetesClientException(
                new KubernetesClientException("boom", 404, null));

        assertThat(resp.getMsg()).contains("boom");
    }
}
