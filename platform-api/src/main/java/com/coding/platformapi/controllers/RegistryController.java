package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.RegistryBrowseRequest;
import com.coding.platformapi.models.RegistryConnectionView;
import com.coding.platformapi.models.RegistrySuggestRequest;
import com.coding.platformapi.models.RegistrySuggestResponse;
import com.coding.platformapi.registry.BrowseResult;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.RegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 镜像仓库的三个只读入口（租户可达）。本层只做 HTTP 绑定与 {@link ResponseData} 包装；
 * 业务与两条硬边界在 {@link RegistryService}（分层约定见 docs/development/backend-layering.md）。
 *
 * <p>权限行见 V2026_10_14_1：三个端点**共用** {@code tenant:registry:catalog}
 * （它们是同一件事「看镜像目录」的三个入口，都只读、都归同一页面同一批人）。
 * **三个都必须有行**，否则启动期交叉校验拒启。
 *
 * <p><b>审计：三个都不标</b> —— 补全是每按键一次的高频只读动作，逐次写审计等于淹掉审计表；
 * 浏览同理。见 spec §8（有意不做，勿当缺口）。这条由 {@code RegistryControllerTest} 钉着。
 */
@Tag(name = "镜像仓库", description = "镜像候选值补全与只读目录浏览（Harbor）")
@RestController
@RequestMapping("/registry")
@RequiredArgsConstructor
public class RegistryController {

    private final RegistryService registryService;

    /** 租户帽：把「别人家的 Harbor 凭据」过滤掉（管理员 base token 为 null = 全量） */
    private final AuthContext auth;

    @Operation(summary = "镜像候选值",
            description = "入参是输入框里的整串。返回**要填进去的完整字符串**，前端整段替换。"
                    + "匹配不到已配连接时返回空且不发请求；对端失败静默降级为空，不弹错")
    @PostMapping("/suggest")
    public ResponseData<RegistrySuggestResponse> suggest(@RequestBody RegistrySuggestRequest request) {
        return new ResponseData<>(registryService.suggest(request.getImagePrefix(), auth.hatTenantId()));
    }

    @Operation(summary = "浏览一层",
            description = "path 为空 = 第一层。面包屑与层级深度由方言决定，前端不认识层级名。"
                    + "失败**会报错**（与 suggest 姿态不同）")
    @PostMapping("/browse")
    public ResponseData<BrowseResult> browse(@RequestBody RegistryBrowseRequest request) {
        return new ResponseData<>(registryService.browse(request, auth.hatTenantId()));
    }

    @Operation(summary = "可浏览的连接",
            description = "窄投影：只回 id/name/type/endpoint，只列启用的镜像仓库类连接。"
                    + "不复用 /credential/list —— 那个要平台管理员的码，租户进页面就会 403")
    @PostMapping("/connections")
    public ResponseData<List<RegistryConnectionView>> connections() {
        return new ResponseData<>(registryService.connections(auth.hatTenantId()));
    }
}
