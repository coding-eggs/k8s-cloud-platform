package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.platformapi.models.PermissionPointRequest;
import com.coding.platformapi.security.PermissionCrossCheck;
import com.coding.platformapi.security.PermissionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 权限点管理（CRUD + 热加载）。
 *
 * <p>安全线（fail-closed）：每个写操作在 DB 写入<b>前</b>，用内存候选规则集
 * （当前 active 行 ± 本次变更）对照活端点跑与启动期同款的交叉校验——会制造裸端点即拒绝、零写入；
 * 通过后才写库，且提交后重读 {@code selectAllActive()} 原子换表（{@link PermissionRegistry#replaceRules}），
 * DB 与运行时规则不漂移。并发双管理员同时编辑的竞态在本平台规模下可接受（文档注明）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

    private static final Set<String> DOMAINS = Set.of("API", "K8S", "Page");
    /**API 域动作 = HTTP 方法（表驱动授权把 action 直接当 method 匹配，故只能是动词） */
    private static final Set<String> API_ACTIONS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "*");
    /**Page 域动作 = 前端路由的可见性动词 VIEW，与 HTTP 无关（不进 URL 规则表，见 rulesOf 的 domain 过滤）。
     // ⚠️ 必须大写：validateFields 先 toUpperCase 再比对；DB 里也统一大写（与 API 域行一致） */
    private static final Set<String> PAGE_ACTIONS = Set.of("VIEW");

    private final PlatformPermissionMapper permMapper;
    private final PermissionRowWriter writer;
    private final PermissionRegistry registry;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    public List<PlatformPermission> list() {
        return permMapper.selectAllActive();
    }

    public PlatformPermission create(PermissionPointRequest req) {
        validateFields(req);
        PlatformPermission row = toRow(ULIDGenerator.generateULID(), new Date(), req);
        assertCoversAllEndpoints(withCandidate(permMapper.selectAllActive(), null, row));
        writer.insert(row); // 单行事务，返回即提交
        applyToRuntime();
        return row;
    }

    public void update(PermissionPointRequest req) {
        validateFields(req);
        PlatformPermission existing = requireActive(req.getId());
        PlatformPermission merged = toRow(existing.getId(), existing.getCreatedAt(), req);
        assertCoversAllEndpoints(withCandidate(permMapper.selectAllActive(), existing.getId(), merged));
        writer.update(merged);
        applyToRuntime();
    }

    public void delete(String id) {
        requireActive(id);
        assertCoversAllEndpoints(withCandidate(permMapper.selectAllActive(), id, null));
        writer.softDelete(id);
        applyToRuntime();
    }

    /** 显式重载（D3）：SQL 直接改库后把运行时对齐——重读 DB → 裸端点校验 → 换表。 */
    public void reload() {
        assertCoversAllEndpoints(permMapper.selectAllActive());
        applyToRuntime();
    }

    /** 写前裸端点校验：候选规则集跑与启动 Runner 同款交叉校验，有缺口即拒绝（零写入）。 */
    private void assertCoversAllEndpoints(List<PlatformPermission> candidate) {
        PermissionRegistry candidateReg = new PermissionRegistry(rulesOf(candidate));
        var gaps = PermissionCrossCheck.uncoveredEndpoints(
                PermissionCrossCheck.liveEndpoints(requestMappingHandlerMapping), candidateReg);
        if (!gaps.isEmpty())
            throw new CloudPlatformException(EnumResponseType.PERMISSION_ENDPOINT_UNCOVERED,
                    "操作会使以下端点失去权限覆盖: " + gaps);
    }

    /** 提交后重读 DB 并原子换表（读方见整旧或整新快照），幽灵规则 WARN 与启动期同口径。 */
    private void applyToRuntime() {
        List<PermissionRegistry.Rule> rules = rulesOf(permMapper.selectAllActive());
        registry.replaceRules(rules);
        var ghosts = PermissionCrossCheck.ghostRules(
                PermissionCrossCheck.liveEndpoints(requestMappingHandlerMapping), registry);
        if (!ghosts.isEmpty())
            log.warn("[RBAC] 以下权限行匹配不到任何已注册 endpoint（幽灵行，不影响运行；"
                    + "计划端点可暂时保留，已删除/改名的须清理）: {}", ghosts);
        log.info("[RBAC] 授权规则已热加载：{} 条 API 规则", rules.size());
    }

    /** 候选规则集 = 当前 active 行 ± 本次变更（update 替换目标行；delete 移除目标行；create 追加）。 */
    private static List<PlatformPermission> withCandidate(List<PlatformPermission> current,
                                                          String replaceId, PlatformPermission newRow) {
        List<PlatformPermission> candidate = new ArrayList<>(current);
        if (replaceId != null) {
            for (int i = 0; i < candidate.size(); i++)
                if (candidate.get(i).getId().equals(replaceId)) {
                    if (newRow == null)
                        candidate.remove(i); // delete：移除目标行
                    else
                        candidate.set(i, newRow); // update：替换目标行
                }
        } else if (newRow != null) {
            candidate.add(newRow);
        }
        return candidate;
    }

    /** 与 PermissionRegistryFactory 同口径：仅 API 域行进规则（resource→URL pattern，action→HTTP method）。 */
    private static List<PermissionRegistry.Rule> rulesOf(List<PlatformPermission> rows) {
        return rows.stream()
                .filter(p -> "API".equals(p.getDomain()))
                .map(p -> new PermissionRegistry.Rule(p.getAction(), p.getResource(), p.getCode()))
                .toList();
    }

    private void validateFields(PermissionPointRequest req) {
        String code = trimToEmpty(req.getCode());
        if (code.isEmpty() || !(code.startsWith("platform:") || code.startsWith("tenant:")))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "权限码必填且须以 platform: 或 tenant: 开头");
        if (!DOMAINS.contains(req.getDomain()))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "权限域须为 API / K8S / Page");
        String action = trimToEmpty(req.getAction()).toUpperCase();
        Set<String> allowed = "Page".equals(req.getDomain()) ? PAGE_ACTIONS : API_ACTIONS;
        if (!allowed.contains(action))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "Page 域动作须为 view；API/K8S 域动作须为 GET / POST / PUT / DELETE / PATCH / *");
        String resource = trimToEmpty(req.getResource());
        if (resource.isEmpty())
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "资源（API 域为 URL 模式 / Page 域为前端路由 path）必填");
        if ("API".equals(req.getDomain()) && !resource.startsWith("/"))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "API 域 URL 模式须以 / 开头（支持 Ant 通配，如 /xxx/**）");
        if ("Page".equals(req.getDomain()) && !resource.startsWith("/"))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "Page 域 resource 须为前端路由 path（以 / 开头，如 /resources/workloads）");
    }

    private PlatformPermission requireActive(String id) {
        if (!StringUtils.hasText(id))
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "权限点 id 必填");
        PlatformPermission p = permMapper.selectByPrimaryKey(id); // XML 已过滤 deleted_at is null
        if (p == null)
            throw new CloudPlatformException(EnumResponseType.PERMISSION_NOT_FOUND, "权限点不存在或已删除: " + id);
        return p;
    }

    private static PlatformPermission toRow(String id, Date createdAt, PermissionPointRequest req) {
        var p = new PlatformPermission();
        p.setId(id);
        p.setDomain(req.getDomain());
        p.setResource(req.getResource().trim());
        p.setAction(req.getAction().trim().toUpperCase());
        p.setCode(req.getCode().trim());
        p.setDescription(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null);
        p.setCreatedAt(createdAt);
        return p;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
