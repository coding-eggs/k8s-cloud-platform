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
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * k8s-server HTTP 内核（{@link K8sResourceClient} / {@link K8sAdminClient} 共用底座）：
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
    private final JsonMapper jsonMapper;

    public K8sServerGateway(@Value("${k8s.server.url:http://127.0.0.1:8080}") String baseUrl, JsonMapper jsonMapper) {
        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        //开通/清理可能跨多个集群，读超时放宽（资源 CRUD 常规秒级）
        factory.setReadTimeout(120_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.jsonMapper = jsonMapper;
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

    /**
     * 调 k8s-server（任意动词）：透传 Authorization，code≠200 原样转 CloudPlatformException。
     * respType 为 ResponseData&lt;T&gt; 完整类型；返回 data。
     */
    @SuppressWarnings("unchecked")
    public <T> T exchange(HttpMethod method, String path, Map<String, String> params, Object body, JavaType respType) {
        String uri = buildUri(path, params);
        StatusBody sb;
        try {
            //不用 retrieve()：401/403 也带 ResponseData JSON，统一按 body.code 判定
            var spec = restClient.method(method)
                    .uri(uri)
                    .headers(this::passThroughAuthorization);
            if (body != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            sb = spec.exchange((request, response) -> new StatusBody(
                    response.getStatusCode().value(), response.bodyTo(String.class)));
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 k8s-server {} {} 失败", method, path, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "调用 k8s-server 失败: " + e.getMessage());
        }
        if (sb.body() == null || sb.body().isBlank()) {
            log.error("k8s-server {} 返回空响应，HTTP {}", path, sb.status());
            throw new CloudPlatformException(EnumResponseType.ERROR, "k8s-server 返回空响应 (HTTP " + sb.status() + ")");
        }
        ResponseData<T> resp;
        try {
            resp = jsonMapper.readValue(sb.body(), respType);
        } catch (Exception e) {
            log.error("解析 k8s-server {} 响应失败：{}", path, sb.body(), e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "k8s-server 响应解析失败");
        }
        if (resp == null || resp.getCode() == null || !resp.getCode().equals(EnumResponseType.SUCCESS.getCode())) {
            Integer code = resp != null && resp.getCode() != null ? resp.getCode() : EnumResponseType.ERROR.getCode();
            String msg = resp != null && resp.getMsg() != null ? resp.getMsg() : "k8s-server 调用失败";
            throw new CloudPlatformException(code, msg);
        }
        return resp.getData();
    }

    /**
     * 流式 GET：响应体原样透传到 out（Pod 日志场景，不整体缓冲）。
     * k8s-server 成功 = text/plain；失败 = ResponseData JSON → 解析后抛 CloudPlatformException。
     */
    public void streamGet(String path, Map<String, String> params, OutputStream out) {
        String uri = buildUri(path, params);
        try {
            restClient.method(HttpMethod.GET)
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

    /**HTTP 状态码 + 原始响应体 */
    private record StatusBody(int status, String body) {
    }
}
