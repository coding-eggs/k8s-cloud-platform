package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 中间件 CR 的镜像改写：没配仓库就原样返回（= 纳管之前的行为）。 */
class ImagesTest {

    @Test
    void rewritesWhenTheRegistryIsGiven() {
        MiddlewareCreateRequestDTO req = new MiddlewareCreateRequestDTO();
        req.setImageRegistryHost("harbor.ackcp.cloud");
        req.setImageRegistryProject("platform");

        assertThat(Images.resolve("quay.io/opstree/redis:v7.0.15", req))
                .isEqualTo("harbor.ackcp.cloud/platform/quay.io/opstree/redis:v7.0.15");
        assertThat(Images.resolve("milvusdb/milvus:v2.6.25", req))
                .isEqualTo("harbor.ackcp.cloud/platform/docker.io/milvusdb/milvus:v2.6.25");
    }

    @Test
    void leavesImageAloneWithoutRegistry() {
        assertThat(Images.resolve("quay.io/opstree/redis:v7.0.15", new MiddlewareCreateRequestDTO()))
                .isEqualTo("quay.io/opstree/redis:v7.0.15");
        assertThat(Images.resolve(null, new MiddlewareCreateRequestDTO())).isNull();
    }
}
