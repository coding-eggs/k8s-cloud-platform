package com.coding.common.models.app;

import lombok.Data;

import java.util.List;

/** platform-api → k8s-server：现读这些对象的就绪状态。 */
@Data
public class AppStatusRequestDTO {

    private String clusterId;

    private List<AppObjectRefDTO> objects;
}
