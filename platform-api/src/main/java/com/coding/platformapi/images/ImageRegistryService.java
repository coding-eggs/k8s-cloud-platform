package com.coding.platformapi.images;

import com.coding.common.models.image.ImageReference;
import com.coding.platformapi.services.ClusterService;
import com.coding.platformapi.services.HarborRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 镜像来源（平台 Harbor / 官方）的解析与改写入口。
 *
 * <p>地址解析顺序：**集群列**（{@code k8s_cluster.image_registry_host}）→ **Harbor 组件的安装记录**
 * （上一次成功安装的 externalHost）→ **平台默认**（{@code platform.image-registry.host}）。
 * 三者都空、或 {@code platform.image-registry.enabled=false} 时视为「官方」，改写是恒等函数。
 *
 * <p>为什么地址不是单独配一份：镜像仓库就是 Harbor，装在哪、用的什么域名，安装记录里已经有了；
 * 再让管理员配一遍必然漂移（历史上 {@code object_storage_url} 就吃过这个亏）。
 */
@Service
@RequiredArgsConstructor
public class ImageRegistryService {

    private final ClusterService clusterService;
    private final HarborRegistryService harborRegistry;

    @Value("${platform.image-registry.host:}")
    String defaultHost = "";

    /** 额外路径前缀；**留空 = 一上游一项目**（镜像的项目名就是上游主机，如 quay.io） */
    @Value("${platform.image-registry.project:}")
    String defaultProject = "";

    @Value("${platform.image-registry.enabled:true}")
    boolean enabled = true;

    /** 该集群的镜像仓库（host 不带 scheme）；未配置 → {@link ImageRegistry#OFFICIAL} */
    public ImageRegistry resolve(String clusterId) {
        if (!enabled) {
            return ImageRegistry.OFFICIAL;
        }
        String host = firstNonBlank(
                clusterService.imageRegistryHost(clusterId),
                harborRegistry.harborHost(clusterId),
                defaultHost);
        if (host == null || host.isBlank()) {
            return ImageRegistry.OFFICIAL;
        }
        String project = firstNonBlank(clusterService.imageRegistryProject(clusterId), defaultProject);
        return new ImageRegistry(stripScheme(host), project == null ? "" : project);
    }

    /**
     * 按来源改写镜像地址：只有 {@code PLATFORM} 且仓库可用时才改，其余一律原样返回。
     *
     * <p>这就是「切回官方 = 行为回到今天」的开关。
     */
    public String rewrite(String clusterId, String image, ImageSource source) {
        if (image == null || image.isBlank() || source != ImageSource.PLATFORM) {
            return image;
        }
        ImageRegistry registry = resolve(clusterId);
        if (!registry.enabled()) {
            return image;
        }
        return ImageReference.parse(image).mirror(registry.host(), registry.project());
    }

    static String stripScheme(String host) {
        String s = host == null ? "" : host.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
