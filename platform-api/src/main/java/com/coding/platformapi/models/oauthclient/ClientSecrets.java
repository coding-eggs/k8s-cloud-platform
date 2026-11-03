package com.coding.platformapi.models.oauthclient;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 本次新生成的明文密钥（其余为 null）。只出现在创建 / 重置 / 改形态的响应里一次，平台不再保存明文 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClientSecrets {

    private String clientSecret;
    private String jwsSecret;
    private String jweSecret;

    @JsonIgnore
    public boolean isEmpty() {
        return clientSecret == null && jwsSecret == null && jweSecret == null;
    }
}
