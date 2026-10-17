package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/** {@code POST /components/apply} 的响应。逐 release 回报，整体成功 = 全部 SUCCESS。 */
@Data
public class ComponentInstallResultDTO {
    private boolean success;
    private String message;
    private List<ReleaseStatusDTO> releases;

    @Data
    public static class ReleaseStatusDTO {
        private String releaseName;
        private String chartVersion;
        /** SUCCESS / FAILED */
        private String status;
        private String message;
    }
}
