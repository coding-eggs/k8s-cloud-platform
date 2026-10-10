package com.coding.platformapi.k8s;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * k8s-server HTTP 内核（{@link K8sClient} / Calico、节点、Pod、生命周期各专用 client 共用底座）：
 * <ul>
 *   <li>认证：透传当前请求的管理员 token（Authorization 头原样转发）</li>
 *   <li>错误：k8s-server 返回 code≠200 时原样转 CloudPlatformException（共用 EnumResponseType）；
 *       空 body = 请求没走到业务层（如安全链默认 401 entry point）</li>
 * </ul>
 */
@Slf4j
@Component
public class K8sServerGateway {

    private final RestClient restClient;

    /**流式透传专用（Pod 日志 follow）：读超时放宽，避免安静容器 >120s 无输出时被掐断 */
    private final RestClient streamRestClient;

    /**
     * api↔k8s-server 内部通信专用 mapper（反序列化 k8s-server 响应用），与共享 {@code JsonMapperConfig} 解耦。
     */
    private final JsonMapper jsonMapper;

    public K8sServerGateway(@Value("${k8s.server.url:http://127.0.0.1:8080}") String baseUrl) {
        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        //开通/清理可能跨多个集群，读超时放宽（资源 CRUD 常规秒级）
        factory.setReadTimeout(120_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        //流式专用：连接超时同常规；读超时放宽到 30 分钟（= 每 30 分钟内只要有日志输出即续命），
        //与 k8s-server 侧异步超时长一致，避免安静容器在 120s 无输出时被本层掐断。
        SimpleClientHttpRequestFactory streamFactory = new SimpleClientHttpRequestFactory();
        streamFactory.setConnectTimeout(10_000);
        streamFactory.setReadTimeout(30 * 60 * 1000);
        this.streamRestClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(streamFactory)
                .build();

        //专用 mapper：只做标准反序列化，null 保持 null。刻意不套用共享配置的 null 值改写
        //（对象→{}、数组→[]、字符串/数字→""、布尔→false），避免 k8s-server 的空对象字段被读成"非空的空对象"。
        //保留 ACCEPT_EMPTY_STRING_AS_NULL_OBJECT：k8s-server 把 null 数字序列化成 ""，需容忍并还原为 null。
        this.jsonMapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build();
    }

    /**ResponseData&lt;T&gt; 类型（T 为运行时类，供类型化反序列化） */
    public JavaType responseType(Class<?> dataClass) {
        return jsonMapper.getTypeFactory().constructParametricType(ResponseData.class, dataClass);
    }

    /**ResponseData&lt;List&lt;T&gt;&gt; 类型 */
    public JavaType listResponseType(Class<?> itemClass) {
        return jsonMapper.getTypeFactory().constructParametricType(ResponseData.class,
                jsonMapper.getTypeFactory().constructCollectionType(List.class, itemClass));
    }

    /**ResponseData&lt;Map&lt;String, List&lt;String&gt;&gt;&gt; 类型（集群 API 能力：group→versions） */
    public JavaType capabilityResponseType() {
        TypeFactory tf = jsonMapper.getTypeFactory();
        JavaType mapType = tf.constructMapType(Map.class,
                tf.constructType(String.class),
                tf.constructCollectionType(List.class, String.class));
        return tf.constructParametricType(ResponseData.class, mapType);
    }

    /**
     * 调 k8s-server（任意动词）：透传 Authorization，code≠200 原样转 CloudPlatformException。
     * respType 为 ResponseData&lt;T&gt; 完整类型；返回 data。
     *
     * <p><b>流式（2026-10-10 方案 A）</b>：响应体从 {@code InputStream} 直接反序列化，<b>不再</b>先读成 String
     * —— 旧实现的峰值是「byte[] + String(≈2×) + 对象图」，大响应（跨命名空间列举）上就是几百 MB。
     * 报错上下文由 {@link ResponsePreviewStream} 限在前 8KB，避免"解析失败时把整包 body 写进日志"。
     */
    public <T> T exchange(HttpMethod method, String path, Map<String, String> params, Object body, JavaType respType) {
        String uri = buildUri(path, params);
        try {
            var spec = restClient.method(method)
                    .uri(uri)
                    .headers(this::passThroughAuthorization);
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            //不用 retrieve()：401/403 也带 ResponseData JSON，统一按 body.code 判定
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                ResponsePreviewStream in = new ResponsePreviewStream(response.getBody());
                ResponseData<T> resp;
                try {
                    resp = jsonMapper.readValue(in, respType);
                } catch (Exception e) {
                    if (in.totalBytes() == 0) {
                        log.error("k8s-server {} 返回空响应，HTTP {}", path, status);
                        throw new CloudPlatformException(EnumResponseType.ERROR,
                                "k8s-server 返回空响应 (HTTP " + status + ")");
                    }
                    log.error("解析 k8s-server {} 响应失败（HTTP {}，共 {} 字节，前 8KB：{}）",
                            path, status, in.totalBytes(), in.previewText(), e);
                    throw new CloudPlatformException(EnumResponseType.ERROR, "k8s-server 响应解析失败");
                }
                if (resp == null || resp.getCode() == null || !resp.getCode().equals(EnumResponseType.SUCCESS.getCode())) {
                    Integer code = resp != null && resp.getCode() != null ? resp.getCode() : EnumResponseType.ERROR.getCode();
                    String msg = resp != null && resp.getMsg() != null ? resp.getMsg() : "k8s-server 调用失败";
                    throw new CloudPlatformException(code, msg);
                }
                return resp.getData();
            });
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 k8s-server {} {} 失败", method, path, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "调用 k8s-server 失败: " + e.getMessage());
        }
    }

    /**
     * 流式 GET：响应体原样透传到 out（Pod 日志场景，不整体缓冲）。
     * k8s-server 成功 = text/plain；失败 = ResponseData JSON → 解析后抛 CloudPlatformException。
     */
    public void streamGet(String path, Map<String, String> params, OutputStream out) {
        String uri = buildUri(path, params);
        try {
            streamRestClient.method(HttpMethod.GET)
                    .uri(uri)
                    .headers(this::passThroughAuthorization)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        MediaType contentType = response.getHeaders().getContentType();
                        boolean json = contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_JSON);
                        if (status != 200 || json) {
                            String body = org.springframework.util.StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                            ErrorInfo err = parseError(body, status);
                            throw new CloudPlatformException(err.code(), err.msg());
                        }
                        response.getBody().transferTo(out);
                        out.flush();
                        return null;
                    });
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 k8s-server 流式接口 {} 失败", path, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "调用 k8s-server 失败: " + e.getMessage());
        }
    }

    private String buildUri(String path, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        if (params != null) {
            params.forEach(builder::queryParam);
        }
        return builder.build().toUriString();
    }

    /**解析 k8s-server 错误响应（ResponseData JSON），失败则退化为带状态码的通用错误 */
    private ErrorInfo parseError(String body, int status) {
        if (body != null && !body.isBlank()) {
            try {
                tools.jackson.databind.JsonNode node = jsonMapper.readTree(body);
                int code = node.path("code").asInt(EnumResponseType.ERROR.getCode());
                String msg = node.path("msg").asString("");
                if (!msg.isBlank()) {
                    return new ErrorInfo(code, msg);
                }
            } catch (Exception ignored) {
                //非 JSON 错误体，走兜底
            }
        }
        return new ErrorInfo(EnumResponseType.ERROR.getCode(), "k8s-server 流式接口返回 HTTP " + status);
    }

    private void passThroughAuthorization(HttpHeaders headers) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
        } else {
            log.warn("无请求上下文，调用 k8s-server 未携带 Authorization（后台任务场景需改用服务身份）");
        }
    }

    /**code + msg */
    private record ErrorInfo(int code, String msg) {
    }

    /**
     * 记录「前 {@value #PREVIEW_BYTES} 字节 + 累计字节数」的包装流。
     * <p>存在的唯一理由：流式解析后 body 已被消费、拿不回来，而报错时**需要**一点上下文 ——
     * 旧实现是 "整包读成 String"，于是解析失败时能把整个 body 写进日志（大响应 = 日志盘 + 堆双爆）。
     * 这里把上下文限制在前 8KB，既够定位（k8s-server 的错误信封很短），又不会把大 body 拖进日志。
     * <p>本仓没引 commons-io，故手写（等价于 TeeInputStream + CountingInputStream 的组合）。
     */
    private static final class ResponsePreviewStream extends FilterInputStream {

        private static final int PREVIEW_BYTES = 8 * 1024;

        private final ByteArrayOutputStream preview = new ByteArrayOutputStream();
        private long total;

        private ResponsePreviewStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                recordByte(b);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                recordBytes(buf, off, n);
            }
            return n;
        }

        private void recordByte(int b) {
            total++;
            if (preview.size() < PREVIEW_BYTES) {
                preview.write(b);
            }
        }

        private void recordBytes(byte[] buf, int off, int len) {
            total += len;
            int room = PREVIEW_BYTES - preview.size();
            if (room > 0) {
                preview.write(buf, off, Math.min(room, len));
            }
        }

        /** 已读总字节数（== 0 即"空响应"）。 */
        private long totalBytes() {
            return total;
        }

        /** 前 8KB 的文本（仅用于日志，可能截断在字符中间 —— 无所谓，它是给人看的）。 */
        private String previewText() {
            return preview.toString(StandardCharsets.UTF_8);
        }
    }
}
