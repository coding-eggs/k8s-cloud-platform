package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.models.PvcResizeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * PVC 业务层：透传 + **扩容那两条规则**（S4.1）。
 *
 * <p>分层约定见 docs/development/backend-layering.md —— 「按产品阈值判断」在 api 层，
 * k8s-server 只提供集群事实。所以「只能往大改」「存储类得开了在线扩容」这类判断落在这里，
 * 而不是让 k8s-server 去懂"什么算合法"。
 *
 * <p>⚠️ {@link #update} 与 {@link #resize} 是两个端点、两件事，**别合并**：
 * 前者同步标签，后者是动存储后端、异步、不可逆的扩容。合成一个 PUT 会让权限与审计都说不清
 * （与工作负载那边 {@code POST /{name}/image}、{@code POST /{name}/pause} 单独成端点同一个理由）。
 */
@Service
@RequiredArgsConstructor
public class PvcService {

    private final K8sClient k8s;
    private final StorageClassService storageClasses;

    public List<PersistentVolumeClaimDTO> list(PersistentVolumeClaimDTO query) {
        return k8s.list(query);
    }

    public PersistentVolumeClaimDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public PersistentVolumeClaimDTO create(PersistentVolumeClaimDTO body) {
        return k8s.create(body);
    }

    /**
     * 更新 —— **只同步标签**。容量改不了，那是 {@link #resize} 的事。
     *
     * <p>⚠️ 带上变化的 {@code storage} 会**明确报错**而不是被静默忽略：
     * 静默忽略的话调用方会以为改成功了。要改容量就换那个端点。
     */
    public PersistentVolumeClaimDTO update(PersistentVolumeClaimDTO body) {
        PersistentVolumeClaimDTO current = require(body.getName(), body.getTenantId(),
                body.getClusterId(), body.getNamespace());
        BigDecimal target = body.getStorage();
        if (target != null && current.getStorage() != null && target.compareTo(current.getStorage()) != 0) {
            throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                    "这个端点只同步标签 —— 改容量请走「调整容量」"
                            + "（POST /resource/pvcs/{name}/resize）。");
        }
        // ⚠️ 读-改-写：以集群上那份为底，只盖标签。不能把请求体直接 SSA 上去 ——
        //    PVC 的 `accessModes` 必填、`storageClassName`/`dataSourceRef` 不可变，
        //    调用方只要漏带一个，要么被 API server 拒，要么把不该动的字段删掉。
        current.setLabels(body.getLabels());
        return k8s.update(current);
    }

    /**
     * 扩容。两条规则：**只能往大改** + **存储类得开了 {@code allowVolumeExpansion}**。
     *
     * <p>为什么非得拦：K8s 对 PVC 的 {@code spec} 是**不可变**的，唯一允许的例外就是"往大改容量"，
     * 且**还要**存储类开了那个开关。不拦的话，用户填小了会拿到一句 K8s 原话，
     * 填大了而存储类没开则在 SSA 那一步才炸。
     *
     * <p>⚠️ **存储类读的是集群上那个对象**，不是请求里带的 —— 否则门控等于让调用方自证清白。
     */
    public PersistentVolumeClaimDTO resize(String name, PvcResizeRequest req) {
        PersistentVolumeClaimDTO current = require(name, req.getTenantId(),
                req.getClusterId(), req.getNamespace());
        BigDecimal target = req.getStorage();
        BigDecimal now = current.getStorage();
        if (target == null || now == null) {
            throw new CloudPlatformException(EnumResponseType.PVC_RESIZE_INVALID,
                    "没带新容量，改不了。要改容量就填一个比现在更大的值。");
        }
        // ⚠️ 相等也拒：那等于什么都没改，放过去就是白跑一次 SSA，还会在集群上留一条事件
        if (target.compareTo(now) <= 0) {
            throw new CloudPlatformException(EnumResponseType.PVC_RESIZE_INVALID,
                    "容量只能往大改：现在是 " + size(now) + "，你填的是 " + size(target)
                            + "。K8s 不支持缩容，要变小只能删了重建。");
        }
        String sc = current.getStorageClassName();
        if (sc == null || sc.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.PVC_RESIZE_INVALID,
                    "这个卷没写存储类（多半是手工绑到某块 PV 上的），线上扩容得由存储类支持，"
                            + "这里不敢替你断言能扩。");
        }
        StorageClassDTO klass = storageClasses.get(sc, req.getTenantId(), req.getClusterId());
        if (klass == null) {
            // ⚠️ fail-closed：读不到就不敢说"能扩"。放过去只会让用户在 SSA 那一步撞上 K8s 原话
            throw new CloudPlatformException(EnumResponseType.PVC_RESIZE_INVALID,
                    "读不到存储类 " + sc + "（它可能已经不存在）—— 没法确认它开没开在线扩容，"
                            + "先确认这个存储类还在、且开了 allowVolumeExpansion。");
        }
        if (!Boolean.TRUE.equals(klass.getAllowVolumeExpansion())) {
            throw new CloudPlatformException(EnumResponseType.PVC_RESIZE_INVALID,
                    "存储类 " + sc + " 没开在线扩容（allowVolumeExpansion）—— 这个卷只能删了重建。"
                            + "（改存储类上的这个开关要集群管理员，而且只对之后新建的卷生效。）");
        }
        current.setStorage(target);
        return k8s.update(current);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    /** 取集群上那一份（读-改-写的底稿）；不存在就明确报错，而不是拿 null 往下走。 */
    private PersistentVolumeClaimDTO require(String name, String tenantId, String clusterId, String namespace) {
        PersistentVolumeClaimDTO current = k8s.get(dto(name, tenantId, clusterId, namespace));
        if (current == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "PVC 不存在：" + name);
        }
        return current;
    }

    /** 字节 → {@code 10Gi} 这种给人看的写法。**只用在报错文案里** —— 数字对不上时才看得懂。 */
    private static String size(BigDecimal bytes) {
        BigDecimal gib = bytes.divide(BigDecimal.valueOf(1024L * 1024 * 1024), 2, RoundingMode.HALF_UP);
        return gib.stripTrailingZeros().toPlainString() + "Gi";
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private PersistentVolumeClaimDTO dto(String name, String tenantId, String clusterId, String namespace) {
        PersistentVolumeClaimDTO d = new PersistentVolumeClaimDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }
}
