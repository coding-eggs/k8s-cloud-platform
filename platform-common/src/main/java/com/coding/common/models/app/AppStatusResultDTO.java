package com.coding.common.models.app;

import lombok.Data;

import java.util.List;

/** k8s-server → platform-api：每个对象一条状态。 */
@Data
public class AppStatusResultDTO {

    private List<AppObjectStatusDTO> objects;
}
