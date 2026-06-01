package com.coding.k8score.config;

import lombok.Data;

@Data
public class ResourceCapability {

    // ===== Workload =====
    private boolean deploymentV1;
    private boolean statefulSetV1;
    private boolean daemonSetV1;

    // ===== Network =====
    private boolean ingressV1;
    private boolean ingressV1Beta1;

    // ===== Batch =====
    private boolean cronJobV1;
    private boolean cronJobV1Beta1;

    // ===== 扩展能力（建议）=====
    private boolean supportHPA;
    private boolean supportVPA;

}
