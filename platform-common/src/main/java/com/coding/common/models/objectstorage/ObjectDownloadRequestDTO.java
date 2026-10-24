package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 下载对象（流式回传；大小上限由 platform-api 先 stat 再判断）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectDownloadRequestDTO {
    private String clusterId;
    private String endpoint;
    private String bucket;
    private String key;
}
