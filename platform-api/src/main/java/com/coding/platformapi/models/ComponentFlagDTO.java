package com.coding.platformapi.models;

import java.util.List;

/**
 * 一个功能的可用性旗标（租户侧投影）。
 * <p>⚠️ **有意不含版本与命名空间** —— 租户不需要知道 istiod 装在哪个命名空间，
 * 而"能少给就少给"是这条边界的默认立场（spec §5.2）。
 *
 * @param available 该功能依赖的组件**全部**处于 INSTALLED_HEALTHY
 * @param reason    人读的一句话（缺什么 / 探测不到），展示用
 * @param missing   缺的组件**展示名**列表（前端可据此高亮）
 */
public record ComponentFlagDTO(boolean available, String reason, List<String> missing) {
}
