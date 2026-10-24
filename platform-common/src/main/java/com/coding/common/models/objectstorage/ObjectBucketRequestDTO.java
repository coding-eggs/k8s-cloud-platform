package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 建桶 / 删桶。endpoint 由 platform-api 从组件快照带下来（k8s-server 不猜集群内地址）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectBucketRequestDTO {
    private String clusterId;
    /** S3 网关地址（默认 http://seaweedfs-s3.platform-storage:8333；业务集群可填暴露后的地址） */
    private String endpoint;
    private String bucket;
}
