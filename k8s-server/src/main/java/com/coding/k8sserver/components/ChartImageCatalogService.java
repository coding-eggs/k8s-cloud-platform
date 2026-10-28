package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.image.ImageCatalog;
import com.coding.common.models.image.ImageReference;
import com.coding.common.models.k8s.dto.ImageListItemDTO;
import com.coding.k8sserver.helm.ChartBundle;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 内置 chart 的镜像清单：遍历 {@code charts/**\/chart/values.yaml}，把每个镜像与它在平台 Harbor 的
 * 对应地址一起列出来。
 *
 * <p>与 {@link ChartImageOverlay} 共用同一套识别口径（{@link ImageCatalog}），所以「清单里列的」
 * 就是「会被改写的」。harbor 组件本身排除（永远官方）。
 */
@Component
public class ChartImageCatalogService {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final ChartBundle charts;

    public ChartImageCatalogService(ChartBundle charts) {
        this.charts = charts;
    }

    public List<ImageListItemDTO> list(String host, String project) {
        Path root = charts.root();
        if (!Files.isDirectory(root)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "内置 chart 目录不存在：" + root);
        }
        List<ImageListItemDTO> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> "values.yaml".equals(p.getFileName().toString()))
                    .filter(p -> p.toString().replace('\\', '/').contains("/chart/"))
                    .sorted()
                    .forEach(file -> {
                        String chart = root.relativize(file).toString().replace('\\', '/');
                        if (chart.contains("/harbor/")) {
                            return;   // Harbor 的镜像永远走官方
                        }
                        try {
                            String content = Files.readString(file);
                            if (content.isBlank()) {
                                return;
                            }
                            Map<String, Object> values = YAML.readValue(content,
                                    new TypeReference<Map<String, Object>>() { });
                            for (String image : ImageCatalog.collect(values)) {
                                if (!seen.add(chart + "|" + image)) {
                                    continue;
                                }
                                String mirrored = host == null || host.isBlank()
                                        ? image
                                        : ImageReference.parse(image).mirror(host, project);
                                out.add(new ImageListItemDTO(chart, image, mirrored));
                            }
                        } catch (Exception ignored) {
                            // 单个 values.yaml 解析失败不影响整份清单
                        }
                    });
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "遍历内置 chart 失败：" + e.getMessage());
        }
        return out;
    }
}
