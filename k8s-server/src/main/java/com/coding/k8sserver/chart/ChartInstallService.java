package com.coding.k8sserver.chart;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.*;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * chart 应用编排（spec §4）。只做"事实与边界"：
 * 拉取 → 渲染 → 静态检查 → 执行 → 用 helm get manifest 复核实际落盘内容。
 * 目录、权限、租户归属判定都在 platform-api。
 */
@Service
public class ChartInstallService {

    private final HelmRunner helm;
    private final ChartFetcher fetcher;
    private final ClusterScopeIndex scopes;
    private final KubernetesClientFactory clients;

    public ChartInstallService(HelmRunner helm, ChartFetcher fetcher,
                               ClusterScopeIndex scopes, KubernetesClientFactory clients) {
        this.helm = helm;
        this.fetcher = fetcher;
        this.scopes = scopes;
        this.clients = clients;
    }

    public ChartInspectResultDTO inspect(ChartInspectRequestDTO req) {
        ChartFetcher.FetchedChart chart = fetcher.fetch(toRef(req.getChart()));
        try {
            ChartInspectResultDTO out = new ChartInspectResultDTO();
            out.setDigest(chart.digest());
            HelmResult values = helm.showValues(chart.tgz());
            out.setDefaultValues(values.ok() ? values.output() : "");
            return out;
        } finally {
            deleteQuietly(chart.tgz());
        }
    }

    public ChartPreviewResultDTO preview(ChartPreviewRequestDTO req) {
        String kubeconfig = clients.adminKubeconfig(req.getClusterId());
        ChartFetcher.FetchedChart chart = fetcher.fetch(toRef(req.getChart()));
        Path values = writeValues(req.getValues());
        try {
            HelmResult rendered = helm.render(kubeconfig, chart.tgz(), values, req.getNamespace());
            if (!rendered.ok()) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "渲染失败：" + rendered.output());
            }
            List<Violation> violations = ManifestInspector.inspect(
                    rendered.output(), req.getNamespace(), req.isStrict(), scopes.forCluster(req.getClusterId()));
            ChartPreviewResultDTO out = new ChartPreviewResultDTO();
            out.setManifest(rendered.output());
            out.setViolations(toDtos(violations));
            out.setObjects(ChartObjects.scan(rendered.output()));
            return out;
        } finally {
            deleteQuietly(values);
            deleteQuietly(chart.tgz());
        }
    }

    public ChartApplyResultDTO apply(ChartApplyRequestDTO req) {
        String kubeconfig = clients.adminKubeconfig(req.getClusterId());
        ChartFetcher.FetchedChart chart = fetcher.fetch(toRef(req.getChart()));
        Path values = writeValues(req.getValues());
        try {
            HelmResult rendered = helm.render(kubeconfig, chart.tgz(), values, req.getNamespace(),
                    req.isUpgrade());
            if (!rendered.ok()) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "渲染失败：" + rendered.output());
            }
            ScopeResolver resolver = scopes.forCluster(req.getClusterId());
            List<Violation> pre = ManifestInspector.inspect(
                    rendered.output(), req.getNamespace(), req.isStrict(), resolver);
            Violation blocking = pre.stream().filter(Violation::blocking).findFirst().orElse(null);
            if (blocking != null) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        blocking.type() + "：" + blocking.message());
            }
            HelmRunner.InstallOptions options = req.isStrict()
                    ? new HelmRunner.InstallOptions(true, true, req.getLabels())
                    : new HelmRunner.InstallOptions(false, false, req.getLabels());
            HelmResult applied = helm.apply(kubeconfig, chart.tgz(), values,
                    req.getReleaseName(), req.getNamespace(), options);

            ChartApplyResultDTO out = new ChartApplyResultDTO();
            out.setSuccess(applied.ok());
            out.setMessage(applied.output());
            out.setLogTail(applied.output());
            if (applied.ok()) {
                // 第三道闸：以实际落盘的 manifest 为准
                HelmResult actual = helm.manifest(kubeconfig, req.getReleaseName(), req.getNamespace());
                List<Violation> post = actual.ok()
                        ? ManifestInspector.inspect(actual.output(), req.getNamespace(), req.isStrict(), resolver)
                        : List.of();
                out.setViolations(toDtos(post));
            }
            return out;
        } finally {
            deleteQuietly(values);
            deleteQuietly(chart.tgz());
        }
    }

    public boolean uninstall(ChartUninstallRequestDTO req) {
        String kubeconfig = clients.adminKubeconfig(req.getClusterId());
        return helm.uninstall(kubeconfig, req.getReleaseName(), req.getNamespace()).ok();
    }

    private ChartFetcher.ChartRef toRef(ChartRefDTO dto) {
        if (dto == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少 chart 引用");
        }
        return new ChartFetcher.ChartRef(dto.getSource(), dto.getRef(), dto.getChartName(),
                dto.getVersion(), dto.getDigest(), dto.getUsername(), dto.getPassword());
    }

    private static Path writeValues(String values) {
        try {
            Path file = Files.createTempFile("values-", ".yaml");
            Files.writeString(file, values == null || values.isBlank() ? "{}\n" : values,
                    StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "写入 values 失败：" + e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不改变结果（与 kubeconfig 同一原则）
        }
    }

    private static List<ChartViolationDTO> toDtos(List<Violation> violations) {
        List<ChartViolationDTO> out = new ArrayList<>();
        for (Violation v : violations) {
            ChartViolationDTO dto = new ChartViolationDTO();
            dto.setSeverity(v.severity());
            dto.setType(v.type());
            dto.setObject(v.object());
            dto.setMessage(v.message());
            out.add(dto);
        }
        return out;
    }
}
