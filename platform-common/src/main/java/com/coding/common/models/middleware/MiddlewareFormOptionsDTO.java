package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;

/**
 * 中间件创建表单的可选项（命名空间 / StorageClass）。
 *
 * <p>为什么由后端算：命名空间要按租户分配表收窄，StorageClass 是集群级、
 * 其读端点只对管理员开放（{@code V2026_10_07_3}）—— 两者都不能让前端自己拼。
 */
@Data
public class MiddlewareFormOptionsDTO {

    /** 当前租户（管理员代建时为全部）可用的命名空间，按名字排序 */
    private List<String> namespaces;

    /** 集群里可选的 StorageClass 名（不含"用集群默认"这一项，前端用空值表达） */
    private List<String> storageClasses;

    /** 本集群是否已装对象存储组件（Milvus 的「平台对象存储」选项要它） */
    private boolean objectStorageAvailable;
}
