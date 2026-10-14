package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.system.PlatformPipelineMapper;
import com.coding.data.models.system.PlatformPipeline;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 流水线**定义**（表 {@code platform_pipeline}）。**画布 JSON 是唯一真相源**（spec §3.2）——
 * 集群里不留 {@code Pipeline} 对象，每次运行即时渲染并内联进 {@code PipelineRun}。
 *
 * <h2>⚠️ 本类不做渲染</h2>
 * 「画布 → PipelineSpec/YAML」与节点元数据都在 **k8s-server**（spec §7 的改定块）：
 * platform-api 明确不依赖 k8s-core/fabric8（见 {@code platform-api/pom.xml}）。
 * 所以：
 * <ul>
 *   <li>{@link #nodeTypes()} 与 {@link #preview(String)} 是**透传**；</li>
 *   <li>{@link #create}/{@link #update} 里的**画布校验就是调一次 render** ——
 *       「保存时就拒掉带环的画布」不需要在这里重写一遍环检测。</li>
 * </ul>
 *
 * <h2>归属与边界</h2>
 * 流水线是**命名空间级**资源（跟 Deployment/Service 一样），租户边界走既有的分配表
 * （{@code platform_tenant_namespace}），与 spec §5.2 一致。**平台管理员（无租户帽）不过滤** ——
 * 与 {@code PersistentVolumeService} 同一条判断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    /** DNS_LABEL 的子集 —— 名字会进 PipelineRun 名（K8s 对象名），必须合法。 */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private final PlatformPipelineMapper mapper;
    private final PlatformTenantNamespaceMapper allocationMapper;
    private final K8sPipelineClient k8s;
    private final AuthContext auth;

    public void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "流水线名只能是小写字母/数字/连字符，且以字母或数字开头结尾：" + name);
        }
    }

    /** 内置节点元数据。**透传 k8s-server** —— 真相源是那边的 {@code PipelineNodeRegistry}。 */
    public JsonNode nodeTypes() {
        return k8s.nodeTypes();
    }

    /** 画布 → YAML。**透传 k8s-server**；它同时是画布校验的入口。 */
    public String preview(String canvasJson) {
        return k8s.render(canvasJson);
    }

    public List<PipelineDTO> list(PipelineDTO query) {
        assertNamespaceAccessible(query.getClusterId(), query.getNamespace());
        return mapper.listByNamespace(query.getClusterId(), query.getNamespace())
                .stream().map(this::toDto).toList();
    }

    public PipelineDTO get(String id) {
        PlatformPipeline p = require(id);
        assertNamespaceAccessible(p.getClusterId(), p.getNamespace());
        return toDto(p);
    }

    public PipelineDTO create(PipelineDTO req) {
        assertNamespaceAccessible(req.getClusterId(), req.getNamespace());
        validateName(req.getName());
        assertCanvasUsable(req.getCanvasJson());
        if (mapper.selectByKey(req.getClusterId(), req.getNamespace(), req.getName()) != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST,
                    "该命名空间下已存在同名流水线：" + req.getName());
        }

        PlatformPipeline p = new PlatformPipeline();
        p.setId(ULIDGenerator.generateULID());
        p.setClusterId(req.getClusterId());
        p.setNamespace(req.getNamespace());
        p.setName(req.getName());
        p.setDescription(req.getDescription());
        p.setCanvasJson(req.getCanvasJson());
        p.setCreatedBy(currentUsername());
        mapper.insert(p);
        return toDto(p);
    }

    /**
     * 改名 / 改描述 / 改画布。
     *
     * <p>⚠️ **归属不可改**：clusterId 与 namespace 取**库里存的那一份**，不取请求里的 ——
     * 流水线的归属跟着它一生的所有运行，允许"搬走"等于允许把历史运行挂到别人的命名空间下。
     */
    public PipelineDTO update(PipelineDTO req) {
        PlatformPipeline p = require(req.getId());
        assertNamespaceAccessible(p.getClusterId(), p.getNamespace());
        validateName(req.getName());
        assertCanvasUsable(req.getCanvasJson());
        if (!p.getName().equals(req.getName())
                && mapper.selectByKey(p.getClusterId(), p.getNamespace(), req.getName()) != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST,
                    "该命名空间下已存在同名流水线：" + req.getName());
        }

        p.setName(req.getName());
        p.setDescription(req.getDescription());
        p.setCanvasJson(req.getCanvasJson());
        mapper.update(p);
        return toDto(p);
    }

    public void delete(String id) {
        PlatformPipeline p = require(id);
        assertNamespaceAccessible(p.getClusterId(), p.getNamespace());
        mapper.deleteById(id);
    }

    // ---- 内部 ----

    /**
     * 画布能不能用 —— **由 k8s-server 的渲染说了算**（带环 / 未知节点类型都在那里抛）。
     *
     * <p>保存这一步就拦下来，用户不必等到点了运行才发现画布连不起来。
     */
    private void assertCanvasUsable(String canvasJson) {
        k8s.render(canvasJson);
    }

    private PlatformPipeline require(String id) {
        PlatformPipeline p = id == null ? null : mapper.selectById(id);
        if (p == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "流水线不存在：" + id);
        }
        return p;
    }

    /** 租户帽态下必须持有该命名空间的分配行；无帽（平台管理员代操作）不过滤。 */
    private void assertNamespaceAccessible(String clusterId, String namespace) {
        String tenantId = auth.hatTenantId();
        if (tenantId == null) {
            return;
        }
        if (allocationMapper.selectByTenantClusterNs(tenantId, clusterId, namespace) == null) {
            throw new CloudPlatformException(EnumResponseType.NAMESPACE_NOT_ACCESSIBLE,
                    "无权访问该命名空间：" + namespace);
        }
    }

    private String currentUsername() {
        TokenUserInfo u = auth.current();
        return u == null ? null : u.getUsername();
    }

    private PipelineDTO toDto(PlatformPipeline p) {
        PipelineDTO d = new PipelineDTO();
        d.setId(p.getId());
        d.setClusterId(p.getClusterId());
        d.setNamespace(p.getNamespace());
        d.setName(p.getName());
        d.setDescription(p.getDescription());
        d.setCanvasJson(p.getCanvasJson());
        d.setCreatedBy(p.getCreatedBy());
        d.setCreatedAt(p.getCreatedAt());
        d.setUpdatedAt(p.getUpdatedAt());
        return d;
    }
}
