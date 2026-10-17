package com.coding.k8sserver.controllers.cluster;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.ComponentInstallRequestDTO;
import com.coding.common.models.k8s.dto.ComponentInstallResultDTO;
import com.coding.common.models.k8s.dto.PlatformAlertingRequestDTO;
import com.coding.common.models.k8s.dto.ReleaseInstallSpecDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.operations.ServerSideApply;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.PlatformAlertingObjects;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群域 - 组件安装/升级（S3.6，**PLATFORM 边界**）。
 *
 * <p>这是平台唯一一处"自己往集群写非资源对象"的入口（helm）。授权在上游 platform-api
 * （{@code platform:cluster:manage}），本层只声明边界并执行。
 *
 * <p>为什么单开一个类：{@code BoundaryAuthorizationManager} 读 handler 所属类；挂到
 * 集群管理的 PLATFORM 类上虽然边界相同，但安装动作的审计与语义不同，独立成类更清楚。
 *
 * <p><b>失败即停</b>：多 release 的 recipe 里后一个通常依赖前一个（Prometheus → Thanos），
 * 前一个失败还继续装第二个只会留下半个栈。
 */
@Tag(name = "集群域-组件安装", description = "安装/升级集群组件（helm，平台管理员）")
@RestController
@RequestMapping("/components")
public class ComponentInstallController implements AccessBoundaryAware {

    /** values 以 JSON 写入 .yaml 文件 —— JSON 是合法的 YAML 子集，helm 能正确解析。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HelmRunner helm;
    private final ChartBundle charts;
    private final KubernetesClientFactory clientFactory;
    private final ResourceAccessResolver accessResolver;

    public ComponentInstallController(HelmRunner helm, ChartBundle charts,
                                      KubernetesClientFactory clientFactory,
                                      ResourceAccessResolver accessResolver) {
        this.helm = helm;
        this.charts = charts;
        this.clientFactory = clientFactory;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/apply")
    @Operation(summary = "安装/升级组件（helm upgrade --install）",
            description = "先 helm template 硬预检，再执行；每个 release 独立回报成功/失败")
    public ResponseData<ComponentInstallResultDTO> apply(@RequestBody ComponentInstallRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (request.getReleases() == null || request.getReleases().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "没有要安装的 release");
        }
        String kubeconfig = clientFactory.adminKubeconfig(request.getClusterId());

        List<ComponentInstallResultDTO.ReleaseStatusDTO> statuses = new ArrayList<>();
        boolean allOk = true;
        String firstError = null;

        for (ReleaseInstallSpecDTO spec : request.getReleases()) {
            ComponentInstallResultDTO.ReleaseStatusDTO st = new ComponentInstallResultDTO.ReleaseStatusDTO();
            st.setReleaseName(spec.getReleaseName());
            st.setChartVersion(spec.getChartVersion());
            Path valuesFile = null;
            try {
                Path chartDir = charts.chartDir(spec.getChartCode(), spec.getChartVersion());
                valuesFile = writeValues(spec.getValues());

                HelmResult rendered = helm.render(kubeconfig, chartDir, valuesFile, spec.getNamespace());
                if (!rendered.ok()) {
                    st.setStatus("FAILED");
                    st.setMessage("预检失败：" + rendered.output());
                    statuses.add(st);
                    allOk = false;
                    if (firstError == null) {
                        firstError = "预检失败：" + rendered.output();
                    }
                    break; // 失败即停
                }
                // 服务端 dry-run：只把输出记为警告，不阻断（CRD 假阴性见 spec §3.1）
                HelmResult dry = helm.dryRunServer(kubeconfig, chartDir, valuesFile,
                        spec.getReleaseName(), spec.getNamespace());
                if (!dry.ok()) {
                    st.setMessage("服务端预检警告（不阻断）：" + dry.output());
                }
                HelmResult applied = helm.apply(kubeconfig, chartDir, valuesFile,
                        spec.getReleaseName(), spec.getNamespace(), labels(request));
                st.setStatus(applied.ok() ? "SUCCESS" : "FAILED");
                st.setMessage(applied.ok() ? st.getMessage() : applied.output());
                statuses.add(st);
                if (!applied.ok()) {
                    allOk = false;
                    if (firstError == null) {
                        firstError = applied.output();
                    }
                    break; // 失败即停
                }
            } catch (Exception e) {
                st.setStatus("FAILED");
                st.setMessage(e.getMessage());
                statuses.add(st);
                allOk = false;
                if (firstError == null) {
                    firstError = e.getMessage();
                }
                break;
            } finally {
                if (valuesFile != null) {
                    try {
                        Files.deleteIfExists(valuesFile);
                    } catch (Exception ignored) {
                        // 与 kubeconfig 同样的原则：清理失败不改变结果
                    }
                }
            }
        }

        ComponentInstallResultDTO out = new ComponentInstallResultDTO();
        out.setSuccess(allOk);
        out.setMessage(firstError);
        out.setReleases(statuses);
        return new ResponseData<>(out);
    }

    /**
     * 平台告警联动（S3.6 批②）：把 Alertmanager 接回平台。
     *
     * <p>两个固定名字的对象，幂等 SSA：Secret（共享密钥）+ AlertmanagerConfig（route/receivers）。
     * token 只在这一跳出现，**不进 helm values** —— 否则它会落进操作记录的 values_snapshot（DB 明文）。
     */
    @PostMapping("/platform-alerting")
    @Operation(summary = "下发平台告警联动对象（Secret + AlertmanagerConfig）",
            description = "固定对象名、幂等 SSA；由 platform-api 在 Prometheus 安装成功后调用")
    public ResponseData<Void> platformAlerting(@RequestBody PlatformAlertingRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        if (request.getNamespace() == null || request.getNamespace().isBlank()
                || request.getWebhookUrl() == null || request.getWebhookUrl().isBlank()
                || request.getToken() == null || request.getToken().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "平台告警联动参数不完整");
        }
        var client = clientFactory.getAdminClient(request.getClusterId());
        client.secrets().inNamespace(request.getNamespace())
                .resource(PlatformAlertingObjects.secret(request.getNamespace(), request.getToken()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        client.genericKubernetesResources(PlatformAlertingObjects.ALERTMANAGER_CONFIG_CRD)
                .inNamespace(request.getNamespace())
                .resource(PlatformAlertingObjects.alertmanagerConfig(
                        request.getNamespace(), request.getWebhookUrl()))
                .fieldManager(ServerSideApply.FIELD_MANAGER).forceConflicts().serverSideApply();
        return new ResponseData<>();
    }

    private static Map<String, String> labels(ComponentInstallRequestDTO request) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("platform.coding/managed", "true");
        labels.put("platform.coding/component", request.getComponentCode());
        labels.put("platform.coding/cluster", request.getClusterId());
        return labels;
    }

    private static Path writeValues(Map<String, Object> values) throws Exception {
        Path file = Files.createTempFile("values-", ".yaml");
        Files.writeString(file, JSON.writeValueAsString(values == null ? Map.of() : values));
        return file;
    }
}
