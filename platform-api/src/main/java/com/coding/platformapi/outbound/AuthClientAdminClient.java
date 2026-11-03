package com.coding.platformapi.outbound;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.oauthclient.ClientAdminRequest;
import com.coding.platformapi.models.oauthclient.ClientAdminView;
import com.coding.platformapi.models.oauthclient.ClientCreated;
import com.coding.platformapi.models.oauthclient.ClientSecrets;
import com.coding.platformapi.models.oauthclient.EnableRequest;
import com.coding.platformapi.models.oauthclient.IdRequest;
import com.coding.platformapi.models.oauthclient.OAuthScope;
import com.coding.platformapi.models.oauthclient.ResetSecretRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * 调 platform-auth 的客户端管理内部接口（spec §4.6）。
 *
 * <p>为什么不在 platform-api 直接写 oauth2_registered_client：对称密钥要用 auth 自己的加密密钥落库，
 * 那把私钥不该复制到第二个服务。本侧只做权限、审计与入口。
 *
 * <p>共享密钥没配就拒启（与 {@code AlertWebhookTokenCheck} 同理）。auth 以 HTTP 401 拒绝密钥时
 * 转成 5000 而不是透传 401 —— 前端把 401 当「用户登录失效」处理，透传会把管理员踢回登录页。
 */
@Slf4j
@Component
public class AuthClientAdminClient {

    public static final String HEADER = "X-Platform-Internal-Token";

    private final RestClient restClient;

    @Autowired
    public AuthClientAdminClient(@Value("${platform.auth.internal-url}") String baseUrl,
                                 @Value("${platform.auth.internal-token:}") String token) {
        this(RestClient.builder().requestFactory(timeouts()), baseUrl, token);
    }

    AuthClientAdminClient(RestClient.Builder builder, String baseUrl, String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("platform.auth.internal-token（env PLATFORM_INTERNAL_TOKEN）未配置："
                    + "OAuth 客户端管理需要它调用 platform-auth 的内部接口，拒绝启动。须与 auth 的 platform.internal.token 同值");
        }
        this.restClient = builder.baseUrl(baseUrl).defaultHeader(HEADER, token).build();
    }

    private static JdkClientHttpRequestFactory timeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    public List<ClientAdminView> list() {
        return post("/list", null, new ParameterizedTypeReference<>() {});
    }

    public ClientAdminView get(IdRequest req) {
        return post("/get", req, new ParameterizedTypeReference<>() {});
    }

    public List<OAuthScope> scopes() {
        return post("/scopes", null, new ParameterizedTypeReference<>() {});
    }

    public ClientCreated create(ClientAdminRequest req) {
        return post("/create", req, new ParameterizedTypeReference<>() {});
    }

    public ClientCreated update(ClientAdminRequest req) {
        return post("/update", req, new ParameterizedTypeReference<>() {});
    }

    public void delete(IdRequest req) {
        post("/delete", req, new ParameterizedTypeReference<ResponseData<Void>>() {});
    }

    public void enable(EnableRequest req) {
        post("/enable", req, new ParameterizedTypeReference<ResponseData<Void>>() {});
    }

    public ClientSecrets resetSecret(ResetSecretRequest req) {
        return post("/reset-secret", req, new ParameterizedTypeReference<>() {});
    }

    private <T> T post(String path, Object body, ParameterizedTypeReference<ResponseData<T>> type) {
        String uri = "/internal/clients" + path;
        ResponseData<T> resp;
        try {
            resp = restClient.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body == null ? "{}" : body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == 401) {
                            throw new CloudPlatformException(EnumResponseType.ERROR,
                                    "platform-auth 拒绝了内部接口密钥：检查两侧 PLATFORM_INTERNAL_TOKEN 是否一致");
                        }
                        return response.bodyTo(type);
                    });
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 platform-auth {} 失败", uri, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "调用 platform-auth 失败: " + e.getMessage());
        }
        if (resp == null || !EnumResponseType.SUCCESS.getCode().equals(resp.getCode())) {
            Integer code = resp != null && resp.getCode() != null ? resp.getCode() : EnumResponseType.ERROR.getCode();
            String msg = resp != null && resp.getMsg() != null ? resp.getMsg() : "platform-auth 调用失败";
            throw new CloudPlatformException(code, msg);
        }
        return resp.getData();
    }
}
