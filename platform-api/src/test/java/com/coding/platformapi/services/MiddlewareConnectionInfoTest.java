package com.coding.platformapi.services;

import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 连接信息必须同时给「人看的行」和「机器用的结构化字段」，否则注入只能靠猜约定。 */
class MiddlewareConnectionInfoTest {

    @Test
    void connInfoCarriesHostPortAndSecretKeys() {
        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("mysql", "demo-mysql.demo.svc.cluster.local", 3306)
                .secret("demo-mysql-secrets", "root", "root")
                .build();

        assertThat(info.getProtocol()).isEqualTo("mysql");
        assertThat(info.getHost()).isEqualTo("demo-mysql.demo.svc.cluster.local");
        assertThat(info.getPort()).isEqualTo(3306);
        assertThat(info.getSecretName()).isEqualTo("demo-mysql-secrets");
        assertThat(info.getUsernameKey()).isEqualTo("root");
        assertThat(info.getPasswordKey()).isEqualTo("root");
    }

    @Test
    void connInfoWithoutCredentialsLeavesSecretNull() {
        MiddlewareConnectionDTO.ConnInfo info = MiddlewareConnectionDTO.ConnInfo
                .of("kafka", "demo-kafka.demo.svc", 9092)
                .build();

        assertThat(info.getSecretName()).isNull();
        assertThat(info.getPasswordKey()).isNull();
    }
}
