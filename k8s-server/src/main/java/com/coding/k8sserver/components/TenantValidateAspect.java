package com.coding.k8sserver.components;

import com.coding.common.components.jwt.JwtComponent;
import com.coding.common.components.jwt.JwtProperties;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.data.models.system.UserTenantInfo;
import com.coding.k8sserver.annotations.TenantValidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class TenantValidateAspect {

    private final JwtComponent<TokenUserInfo> jwtComponent;
    private final PlatformTenantMapper platformTenantMapper;
    private final JwtProperties jwtProperties;

    @Around("@annotation(tenantValidate)")
    public Object around(ProceedingJoinPoint joinPoint, TenantValidate tenantValidate) throws Throwable {

        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        TokenUserInfo userInfo = jwt.getClaim(jwtProperties.getDataKey());

        UserTenantInfo tenantInfo = userInfo.getTenantInfo();
        if (tenantInfo == null || tenantInfo.getTenantId() == null) {
            throw new CloudPlatformException(EnumResponseType.TOKEN_TENANT_MISSING);
        }

        String tokenTenantId = tenantInfo.getTenantId();
        Object[] args = joinPoint.getArgs();

        // Extract namespace, tenantId, clusterId from DTO arguments
        String requestTenantId = null;
        String namespace = null;
        String clusterId = null;

        for (Object arg : args) {
            if (arg == null || isSimpleType(arg.getClass())) continue;
            if (requestTenantId == null) requestTenantId = extractFieldValue(arg, tenantValidate.tenantField());
            if (namespace == null) namespace = extractFieldValue(arg, tenantValidate.namespaceField());
            if (clusterId == null) clusterId = extractFieldValue(arg, "clusterId");
        }

        // 1. Validate tenantId consistency
        if (requestTenantId != null && !tokenTenantId.equals(requestTenantId)) {
            log.warn("Tenant mismatch: token={}, request={}", tokenTenantId, requestTenantId);
            throw new CloudPlatformException(EnumResponseType.TENANT_MISMATCH);
        }

        // 2. Validate namespace accessibility for the tenant
        if (namespace != null && clusterId != null) {
            validateNamespaceAccess(clusterId, tokenTenantId, namespace);
        }

        return joinPoint.proceed();
    }

    private String extractToken() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new CloudPlatformException(EnumResponseType.NON_AUTH_ENTRY_POINT);
        }
        HttpServletRequest request = attrs.getRequest();
        String token = request.getHeader("Authorization");
        if (!StringUtils.hasText(token)) {
            throw new CloudPlatformException(EnumResponseType.NON_AUTH_ENTRY_POINT);
        }
        return token;
    }

    private boolean isSimpleType(Class<?> clazz) {
        return clazz.isPrimitive() || clazz.isEnum()
                || Number.class.isAssignableFrom(clazz)
                || Boolean.class.isAssignableFrom(clazz)
                || String.class.isAssignableFrom(clazz);
    }

    private String extractFieldValue(Object obj, String fieldName) {
        if (fieldName == null || obj == null) return null;
        String getterPrefix = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            Method method = obj.getClass().getMethod(getterPrefix);
            Object value = method.invoke(obj);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void validateNamespaceAccess(String clusterId, String tenantId, String namespace) {
        boolean hasAccess = platformTenantMapper.hasNamespaceAccess(tenantId, clusterId, namespace);
        if (!hasAccess) {
            log.warn("Tenant {} cannot access namespace {} in cluster {}", tenantId, namespace, clusterId);
            throw new CloudPlatformException(EnumResponseType.NAMESPACE_NOT_ACCESSIBLE);
        }
    }

}
