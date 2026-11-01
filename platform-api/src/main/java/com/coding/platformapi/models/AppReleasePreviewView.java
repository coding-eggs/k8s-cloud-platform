package com.coding.platformapi.models;

import java.util.List;

/**
 * 发布前预览。changes 是全部待发布改动，selected 标出本次勾选的；
 * requiredKeys 是勾选项引用了、却尚未发布也没勾选的服务；problems 非空时不能发布；
 * warnings 只是提示（如疑似明文凭据），永远不阻断发布。
 */
public record AppReleasePreviewView(List<Change> changes, List<String> pendingKeys, List<String> selectedKeys,
                                    List<String> requiredKeys, List<String> addedObjects,
                                    List<String> removedObjects, List<String> problems, List<String> warnings) {

    /** type：ADDED / CHANGED / DELETED；fields：CHANGED 时变化的字段名 */
    public record Change(String key, String type, List<String> fields, String fromImage, String toImage) {
    }
}
