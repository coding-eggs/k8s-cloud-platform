package com.coding.platformapi.models;

import lombok.Data;

import java.util.List;

/**
 * 一条组件的完整状态（平台侧投影）。
 *
 * <p>⚠️ {@code state} 是<b>四态</b>，不是布尔：{@code INSTALLED_HEALTHY} / {@code INSTALLED_UNHEALTHY} /
 * {@code NOT_INSTALLED} / {@code UNKNOWN}。{@code UNKNOWN}（探测失败 / 未探测）与
 * {@code NOT_INSTALLED}（探测成功但没命中）是相反结论，前端不得合并渲染。
 */
@Data
public class ComponentStatusDTO {
    private String code;
    private String name;
    /** 四态之一，见类注释 */
    private String state;
    /** 实测版本（app.kubernetes.io/version 标签或 image tag）；只有 CRD 判据 / 未命中 → null */
    private String version;
    /** 命中的工作负载所在命名空间 */
    private String namespace;
    private Integer ready;
    private Integer desired;
    /** 人读的一句话：命中数 / "n/m 就绪" / "在平台已知的位置没找到" */
    private String detail;
    /** 它支撑的平台功能 code（依赖关系只在目录里写一遍，这里是派生出来的） */
    private List<String> features;
    /**
     * 平台**可安装**的 chart 版本（来自 recipe；空 = 没有 recipe，不支持安装）。
     * ⚠️ 这是 chart 版本，不是上面 {@code version} 的应用版本 —— 两套编号。
     */
    private List<String> availableVersions;
    /** 可编辑配置项（⚙️，来自 recipe）；契约值（🔒）不在其中 */
    private List<EditableFieldDTO> editableFields;
}
