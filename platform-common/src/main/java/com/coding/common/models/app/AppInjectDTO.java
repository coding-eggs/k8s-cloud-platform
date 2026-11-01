package com.coding.common.models.app;

import lombok.Data;

/**
 * 注入到组件环境变量里的一条。{@code value} 与 {@code secretName} 互斥：
 * 凭据永远走 Secret 引用，密码/令牌不会以明文出现在渲染清单里。
 */
@Data
public class AppInjectDTO {

    private String name;

    /** 普通值；引用 Secret 时为 null */
    private String value;

    private String secretName;

    private String secretKey;

    /** true：{@code value} 含 {@code $(_REF_n)}，渲染时必须直接写进容器 env（不进 ConfigMap）。 */
    private boolean inline;

    public static AppInjectDTO value(String name, String value) {
        AppInjectDTO d = new AppInjectDTO();
        d.setName(name);
        d.setValue(value);
        return d;
    }

    public static AppInjectDTO secret(String name, String secretName, String secretKey) {
        AppInjectDTO d = new AppInjectDTO();
        d.setName(name);
        d.setSecretName(secretName);
        d.setSecretKey(secretKey);
        return d;
    }
}
