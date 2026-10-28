package com.coding.k8sserver.middleware;

import com.coding.common.models.image.ImageReference;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;

/**
 * 中间件 CR 里镜像地址的改写入口。
 *
 * <p>与组件侧共用 {@link ImageReference} 的规则（保留上游主机路径、幂等），
 * 只是这里由请求 DTO 携带仓库地址（k8s-server 不读平台库）。
 */
final class Images {

    private Images() {
    }

    /** 扩缩容路径同款（重写 Pod 模板时镜像不能丢回上游） */
    static String resolve(String image, com.coding.common.models.middleware.MiddlewareScaleRequestDTO req) {
        if (req == null || image == null || image.isBlank()) {
            return image;
        }
        return mirror(image, req.getImageRegistryHost(), req.getImageRegistryProject());
    }

    /** 没配仓库就原样返回 —— 这就是「切回官方 = 行为回到今天」 */
    static String resolve(String image, MiddlewareCreateRequestDTO req) {
        if (req == null || image == null || image.isBlank()) {
            return image;
        }
        return mirror(image, req.getImageRegistryHost(), req.getImageRegistryProject());
    }

    private static String mirror(String image, String host, String project) {
        if (host == null || host.isBlank() || project == null || project.isBlank()) {
            return image;
        }
        return ImageReference.parse(image).mirror(host.trim(), project.trim());
    }
}
