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

    /**
     * 这个 type 是不是一个已实现的镜像仓库方言。
     *
     * <p><b>本方法是「哪些 type 属于镜像仓库」的唯一真相源</b> —— 调用方**不要**再手写一份
     * `List.of("HARBOR")` 之类的清单：那样加一档时忘了改它，会变成「全测绿但新档静默失效」
     * （连接不出现在浏览页下拉里、补全永不匹配、browse 判「连接不存在」）。
     */
    public boolean supports(String type) {
        return type != null && byType.containsKey(type);
    }
}
