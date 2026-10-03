package com.coding.platformapi.configs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 命名空间受保护名单（系统命名空间黑名单）。
 * <p>
 * 平台默认全纳管——不再用 managed-by 标签作为编辑/删除门槛；仅由本名单挡住少数不可动的
 * 系统 ns（kube-system、calico-system 等）：命中者拒绝一切写操作（编辑 / 删除 / 配额 / 限制范围），
 * 读（列表 / 详情 / YAML）保持开放。名单在 application.yaml 维护（单一事实源，见 {@code platform.namespace.protected-namespaces}）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.namespace")
public class NamespaceProtectionProperties {

    /** 受保护命名空间名（精确匹配，须与集群内真实 ns 名一致）；空 = 不额外保护任何 ns */
    private List<String> protectedNamespaces = new ArrayList<>();
}
