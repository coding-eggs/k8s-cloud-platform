package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.security.AuthContext;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.app.AppServiceDesired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 服务挂载的来源准入：{@code hostPath} 直接读写节点文件系统（能读 kubelet 的凭据、容器运行时数据），
 * 只对平台管理员开放；租户保存、灰度里改配置都要过这一关。
 *
 * <p>判据与前端 permission store 的 ADMIN_ROLE 同源：token 的 {@code platformRoles} 含 {@code admin}。
 * 只拦「带 hostPath 的保存」，管理员保存后渲染原样下发，发布路径不再重复校验。
 */
@Component
public class AppHostPathGuard {

    /** platform_role.code（内置管理员角色） */
    static final String ADMIN_ROLE = "admin";

    private final AuthContext authContext;

    public AppHostPathGuard(AuthContext authContext) {
        this.authContext = authContext;
    }

    public void assertAllowed(Map<String, Object> desired) {
        if (!AppServiceDesired.usesHostPath(desired) || isPlatformAdmin()) {
            return;
        }
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "hostPath 挂载只允许平台管理员使用，请改用 PVC / emptyDir / ConfigMap / Secret");
    }

    private boolean isPlatformAdmin() {
        TokenUserInfo me = authContext.current();
        return me != null && me.getPlatformRoles() != null && me.getPlatformRoles().contains(ADMIN_ROLE);
    }
}
