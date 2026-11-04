package com.coding.platformapi.security;

import com.coding.common.components.permission.PermissionCrossCheck.Endpoint;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 枚举主 MVC 映射的全部活端点（无 method 条件 → {@code "*"}）。核账本身（{@code PermissionCrossCheck}）在
 * platform-common，是纯逻辑；这一步依赖 webmvc，故留在各服务里。
 * 启动交叉校验（PermissionCrossCheckRunner）与权限点管理写前校验共用同一语义。
 */
public final class MvcEndpoints {

    private MvcEndpoints() {}

    public static List<Endpoint> live(RequestMappingHandlerMapping handlerMapping) {
        List<Endpoint> eps = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
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
        return eps;
    }
}
