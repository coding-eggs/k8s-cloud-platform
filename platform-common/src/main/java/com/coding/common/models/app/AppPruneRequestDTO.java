package com.coding.common.models.app;

import lombok.Data;

import java.util.List;

/**
 * platform-api → k8s-server：删除差集对象。
 *
 * <p>{@code deletePvc=false}（默认）时跳过 PVC —— 它承载数据，误删不可恢复；
 * 只有节点被移除且用户显式确认时才置 true。
 */
@Data
public class AppPruneRequestDTO {

    private String clusterId;

    private List<AppObjectRefDTO> objects;

    private boolean deletePvc;
}
