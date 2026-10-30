package com.coding.platformapi.registry;

import java.util.List;

/**
 * 浏览一层的结果。设计见 docs/superpowers/specs/2026-10-14-registry-design.md §5.2。
 *
 * <p><b>面包屑由后端给、层级深度由方言定</b> —— 前端不认识任何层级名（Harbor 是
 * project→仓库→tag，Artifactory 是仓库→路径→artifact）。这是「加一档前端不用改」的关键。
 *
 * @param breadcrumb 从根到当前层的路径，供点击回跳
 * @param children   当前层的子节点
 */
public record BrowseResult(List<Crumb> breadcrumb, List<Node> children) {

    /** 面包屑的一节。{@code path} 是**累积**路径，点击即回到该层。 */
    public record Crumb(String label, List<String> path) {
    }

    /**
     * 当前层的一个节点。
     *
     * @param hasChildren 前端据此决定这一行能不能点进去
     * @param copyText    非空 = 这一行可「复制镜像地址」（叶子节点才有）
     * @param details     [{label, value}]，**前端表格的列由它决定**，不写死
     * @param badge       归属标记：Harbor 根层的项目是「公开读」还是「租户」；
     *                    其它层级与其它方言为 null（不要为了对齐硬塞空串）
     */
    public record Node(String name, boolean hasChildren, String copyText, List<Detail> details,
                       String badge) {

        /** 老形状（无标记）：非项目层级与其它方言都用它。 */
        public Node(String name, boolean hasChildren, String copyText, List<Detail> details) {
            this(name, hasChildren, copyText, details, null);
        }
    }

    public record Detail(String label, String value) {
    }
}
