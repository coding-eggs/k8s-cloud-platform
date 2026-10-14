package com.coding.platformapi.registry;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 {@code platform_credential.type} 查方言。设计见 spec §2.2。
 *
 * <p>Spring 会把所有 {@link RegistryDialect} bean 注进来 —— 加一档只要多一个 {@code @Component}，
 * 本类不用改。
 */
@Component
public class RegistryDialectRegistry {

    private final Map<String, RegistryDialect> byType = new HashMap<>();

    public RegistryDialectRegistry(List<RegistryDialect> dialects) {
        for (RegistryDialect d : dialects) {
            byType.put(d.type(), d);
        }
    }

    /** 查不到 = 枚举里有值但没人实现。护栏测试会在构建期先拦住这种情况。 */
    public RegistryDialect of(String type) {
        RegistryDialect d = byType.get(type);
        if (d == null) {
            throw new CloudPlatformException(EnumResponseType.REGISTRY_DIALECT_UNSUPPORTED,
                    "该镜像仓库类型尚未实现: " + type);
        }
        return d;
    }
}
