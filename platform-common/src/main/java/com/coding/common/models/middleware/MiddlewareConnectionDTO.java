package com.coding.common.models.middleware;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 实例的连接方式（创建成功后弹给用户）。
 *
 * <p>只给**集群内**地址：平台不替用户做集群外暴露（Ingress/NodePort 属于租户自己的网络决定）。
 * 凭据给的是「去哪个 Secret 的哪个键取」，不把口令回传给前端。
 */
@Data
public class MiddlewareConnectionDTO {

    public record Line(String label, String value) {
    }

    private String instanceName;

    private String namespace;

    /** 集群内访问地址（Service DNS + 端口） */
    private List<Line> endpoints;

    /** 凭据位置（用户名 / 密码在哪个 Secret 的哪个键） */
    private List<Line> credentials;

    /** 附加说明（无认证、需要集群内访问等） */
    private String note;

    /** 机器可用的结构化连接信息（应用编排的依赖注入只认它，不要在别处再拼一遍约定）。 */
    private ConnInfo info;

    /** 注入用的连接信息：地址 + 凭据**位置**（永远是 Secret 名 + 键，不含口令）。 */
    @lombok.Data
    public static class ConnInfo {

        /** mysql / postgresql / redis / kafka / rabbitmq / elasticsearch / nacos / milvus */
        private String protocol;

        /** 集群内地址（Service DNS 名或完整 URL） */
        private String host;

        private Integer port;

        private String secretName;

        private String usernameKey;

        private String passwordKey;

        /** 需要额外约定的键（例如 ES 的 index 前缀）；v1 只读不写。 */
        private Map<String, String> extra = Map.of();

        public static Builder of(String protocol, String host, Integer port) {
            return new Builder(protocol, host, port);
        }

        public static class Builder {

            private final ConnInfo info = new ConnInfo();

            Builder(String protocol, String host, Integer port) {
                info.protocol = protocol;
                info.host = host;
                info.port = port;
            }

            /** 凭据在哪个 Secret 的哪个键；无认证的中间件不要调它。 */
            public Builder secret(String secretName, String usernameKey, String passwordKey) {
                info.secretName = secretName;
                info.usernameKey = usernameKey;
                info.passwordKey = passwordKey;
                return this;
            }

            public Builder extra(Map<String, String> extra) {
                info.extra = extra == null ? Map.of() : extra;
                return this;
            }

            public ConnInfo build() {
                return info;
            }
        }
    }
}
