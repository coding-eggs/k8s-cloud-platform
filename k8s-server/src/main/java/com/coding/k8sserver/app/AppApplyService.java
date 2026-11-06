package com.coding.k8sserver.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.kube.ManifestApplier;
import org.springframework.stereotype.Service;

/** 只做事实与落盘：apply 一次合并后的 manifest；prune 按平台给的清单删。 */
@Service
public class AppApplyService {

    private final ManifestApplier manifests;
    private final KubernetesClientFactory clients;

    public AppApplyService(ManifestApplier manifests, KubernetesClientFactory clients) {
        this.manifests = manifests;
        this.clients = clients;
    }

    public void apply(AppApplyRequestDTO req) {
        String kubeconfig = kubeconfig(req.getClusterId());
        // 渲染阶段已强制每个对象声明目标命名空间（AppRenderService），这里不需要默认命名空间
        HelmResult r = manifests.applyServerSide(kubeconfig, req.getManifest(), null);
        if (!r.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "应用清单写入失败：" + r.output());
        }
    }

    /**
     * 删除差集对象。PVC 默认跳过：它承载数据，误删不可恢复；只有节点被移除且用户显式勾选时才删。
     */
    public int prune(AppPruneRequestDTO req) {
        int deleted = 0;
        for (AppObjectRefDTO ref : req.getObjects()) {
            if ("PersistentVolumeClaim".equals(ref.getKind()) && !req.isDeletePvc()) {
                continue;
            }
            deleteObject(req.getClusterId(), ref);
            deleted++;
        }
        return deleted;
    }

    /** 最小 manifest 按引用删除；对象已不存在视为成功。 */
    void deleteObject(String clusterId, AppObjectRefDTO ref) {
        String manifest = """
                apiVersion: %s
                kind: %s
                metadata:
                  name: %s
                  namespace: %s
                """.formatted(ref.getApiVersion(), ref.getKind(), ref.getName(), ref.getNamespace());
        HelmResult r = manifests.deleteManifest(kubeconfig(clusterId), manifest, ref.getNamespace());
        if (!r.ok()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "清理对象失败（" + ref.getKind() + "/" + ref.getName() + "）：" + r.output());
        }
    }

    private String kubeconfig(String clusterId) {
        return clients == null ? "" : clients.adminKubeconfig(clusterId);
    }
}
