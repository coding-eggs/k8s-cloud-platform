package com.coding.platformapi.security;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TenantContextResolver {

    /** 生效租户 id：自管校验一致，代管要求显式传。 */
    public String requireContext(String tokenTenantId, String requestTenantId) {
        if (StringUtils.hasText(tokenTenantId)) {
            if (!tokenTenantId.equals(requestTenantId)) {
                throw new CloudPlatformException(EnumResponseType.TENANT_MISMATCH);
            }
            return tokenTenantId;
        }
        if (!StringUtils.hasText(requestTenantId)) {
            throw new CloudPlatformException(EnumResponseType.TOKEN_TENANT_MISSING);
        }
        return requestTenantId; // 代管：platform:member:manage 已放行
    }
}
