package com.coding.platformapi.models;

import lombok.Data;

/** 只带 id 的请求（删除）。 */
@Data
public class CredentialIdRequest {

    private String id;
}
