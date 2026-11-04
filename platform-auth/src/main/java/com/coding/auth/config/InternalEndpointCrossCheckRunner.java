package com.coding.auth.config;

import com.coding.common.components.permission.PermissionCrossCheck;
import com.coding.common.components.permission.PermissionCrossCheck.Endpoint;
import com.coding.common.components.permission.PermissionRegistry;
import com.coding.common.components.permission.PlatformServices;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动自检（网关 spec §8.5）：auth 的每个 {@code /internal/**} 端点都必须有 service=platform-auth 的权限行，
 * 缺一条拒启。
 *
 * <p>为什么要在 auth 这一侧查：接口级权限只在网关判，auth 自己只要求「已认证」。新加一个内部端点忘了配行，
 * 网关会对它一律拒绝（表里没有、又不豁免）—— 功能静默不可用；在这里拒启，漏配在部署时就暴露。
 *
 * <p>⚠️ 与迁移同批：本类上线时库里必须已有这些行（V2026_11_04_1 的 JWK 行、V2026_11_04_2 挪过来的客户端管理行）。
 *
 * <p>注入说明：构造器字段名刻意等于 bean 名 {@code requestMappingHandlerMapping} —— auth 有 actuator，
 * 同类型有多个 bean，按参数名回退到 bean 名匹配选中 MVC 主映射（同 api 的 PermissionCrossCheckRunner）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalEndpointCrossCheckRunner implements SmartInitializingSingleton {

    private static final String INTERNAL_PREFIX = "/internal/";

    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    private final PlatformPermissionMapper permissionMapper;

    @Override
    public void afterSingletonsInstantiated() {
        List<Endpoint> eps = endpoints(requestMappingHandlerMapping);
        verify(eps, permissionMapper.selectAllActive());
        log.info("[RBAC] auth 内部端点交叉校验通过：{} 个 /internal/** 端点均有权限行",
                eps.stream().filter(e -> e.pattern().startsWith(INTERNAL_PREFIX)).count());
    }

    /** 只看 {@code /internal/**}；对照 API 域、service=platform-auth 的行。缺行抛异常并点名。 */
    public static void verify(List<Endpoint> endpoints, List<PlatformPermission> rows) {
        List<Endpoint> internal = endpoints.stream().filter(e -> e.pattern().startsWith(INTERNAL_PREFIX)).toList();
        PermissionRegistry registry = new PermissionRegistry(rows.stream()
                .filter(p -> "API".equals(p.getDomain()))
                .map(PermissionRegistry.Rule::from)
                .filter(r -> PlatformServices.AUTH.equals(r.service()))
                .toList());
        var gaps = PermissionCrossCheck.uncoveredEndpoints(PlatformServices.AUTH, internal, registry);
        if (!gaps.isEmpty()) {
            String msg = "[RBAC] 以下 auth 内部端点没有 service=platform-auth 的权限行（网关会一律拒绝），启动失败: " + gaps;
            log.error(msg);
            throw new IllegalStateException(msg);
        }
    }

    private static List<Endpoint> endpoints(RequestMappingHandlerMapping handlerMapping) {
        List<Endpoint> eps = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            var methods = info.getMethodsCondition().getMethods();
            for (String p : info.getPatternValues()) {
                if (methods.isEmpty()) {
                    eps.add(new Endpoint("*", p));
                } else {
                    methods.forEach(m -> eps.add(new Endpoint(m.name(), p)));
                }
            }
        }
        return eps;
    }
}
