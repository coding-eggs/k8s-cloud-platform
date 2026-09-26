package com.coding.platformapi.security;

import com.coding.platformapi.security.PermissionCrossCheck.Endpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 启动期交叉校验：枚举真实 RequestMappingHandlerMapping 的全部端点，
 * 任一端点既无权限行（PermissionRegistry）也不在豁免清单（ExemptPaths）→ 抛异常阻断启动。
 * 这是表驱动授权（Task 10）的安全网：漏配在部署时暴露，而不是运行时静默 403。
 *
 * <p>Task 10 评审遗留（本校验存在的意义，改动 ExemptPaths 前必读）：
 * 豁免前缀（尤其 {@code /resource/**}）是整族吞掉的——若日后有人把非资源域的管理端点
 * 挂到某个豁免前缀之下，该端点永远不会需要权限行，也永远不会被授予任何 code，
 * 授权语义退化为"authenticated 即过"且无人察觉。交叉校验只能兜住"非豁免且无行"
 * 的缺口；"管理端点误入豁免前缀"不在其检测范围，新增 controller 时须人工确认
 * 其前缀归属，勿把管理域路径塞进 ExemptPaths。
 *
 * <p>注入说明：构造器字段名刻意等于 bean 名 {@code requestMappingHandlerMapping}。
 * platform-api 当前无 actuator 依赖，该类型只有唯一 bean；若日后引入 actuator
 * （其 {@code WebMvcEndpointHandlerMapping} 是子类，将出现多候选），Spring 按参数名
 * 回退到 bean 名匹配可自动选中 MVC 主映射（actuator 端点走独立映射与 {@code /actuator/**} 豁免，双保险）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionCrossCheckRunner implements SmartInitializingSingleton {

    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final PermissionRegistry registry;

    @Override
    public void afterSingletonsInstantiated() {
        List<Endpoint> eps = new ArrayList<>();
        for (RequestMappingInfo info : requestMappingHandlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPatternValues();
            var methods = info.getMethodsCondition().getMethods();
            if (patterns.isEmpty()) {
                continue;
            }
            for (String p : patterns) {
                if (methods.isEmpty()) {
                    eps.add(new Endpoint("*", p));
                } else {
                    methods.forEach(m -> eps.add(new Endpoint(m.name(), p)));
                }
            }
        }
        var gaps = PermissionCrossCheck.uncoveredEndpoints(eps, registry);
        if (!gaps.isEmpty()) {
            String msg = "[RBAC] 以下 endpoint 既无权限行也不在豁免清单，启动失败: " + gaps;
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        // §5.3 反向核账：规则匹配不到任何真实端点 = 幽灵行。只 WARN（v1 有意保留三条
        // "计划端点"行：/tenant/update、/user/update、/role/update，其 warn 即"未交付"标记）。
        var ghosts = PermissionCrossCheck.ghostRules(eps, registry);
        if (!ghosts.isEmpty()) {
            log.warn("[RBAC] 以下权限行匹配不到任何已注册 endpoint（幽灵行，不影响启动；"
                    + "计划端点可暂时保留，已删除/改名的须清理 seed）: {}", ghosts);
        }
        log.info("[RBAC] 交叉校验通过：{} 个 endpoint 全部有权限声明或豁免", eps.size());
    }
}
