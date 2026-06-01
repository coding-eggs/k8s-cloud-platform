package com.coding.auth.controller;

import com.coding.auth.client.OAuth2Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;

@RequiredArgsConstructor
@Controller
public class AuthorizationConsentController {

    private static final String CONSENT_PAGE = "/consent.html";

    private final RegisteredClientRepository registeredClientRepository;
    private final OAuth2AuthorizationConsentService authorizationConsentService;

    @GetMapping(value = "/oauth2/consent")
    public String consent(Principal principal,
                          @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
                          @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
                          @RequestParam(OAuth2ParameterNames.STATE) String state,
                          @RequestParam(name = OAuth2ParameterNames.USER_CODE, required = false) String userCode) {
        StringBuilder url = new StringBuilder(CONSENT_PAGE);
        url.append("?client_id=").append(encode(clientId));
        url.append("&scope=").append(encode(scope));
        url.append("&state=").append(encode(state));
        url.append("&username=").append(encode(principal.getName()));
        if (userCode != null && !userCode.isEmpty()) {
            url.append("&user_code=").append(encode(userCode));
        }
        return "redirect:" + url;
    }

    @GetMapping("/consent/details")
    @ResponseBody
    public ScopeList getConsentDetails(
            @RequestParam("principalName") String principalName,
            @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
            @RequestParam(OAuth2ParameterNames.SCOPE) String scope) {

        RegisteredClient registeredClient = this.registeredClientRepository.findByClientId(clientId);

        OAuth2AuthorizationConsent currentAuthorizationConsent =
                this.authorizationConsentService.findById(registeredClient.getId(), principalName);

        Set<String> authorizedScopes = (currentAuthorizationConsent != null)
                ? currentAuthorizationConsent.getScopes()
                : Collections.emptySet();

        // 解析请求的 scopes
        Set<String> requestedScopes = new HashSet<>(List.of(StringUtils.delimitedListToStringArray(scope, " ")));

        // 分离需要审批的 scopes 和 已经批准过的 scopes
        Set<String> scopesToApprove = new HashSet<>();

        Set<String> previouslyApprovedScopes = new HashSet<>();

        for (String s : requestedScopes) {
            if (authorizedScopes.contains(s)) {
                previouslyApprovedScopes.add(s);
            } else {
                scopesToApprove.add(s);
            }
        }

        // ==================== 关键修改部分 ====================
        // 准备带有描述的 Scope 对象列表
        List<OAuth2Scope> scopesToApproveList = scopesToApprove.stream()
                .map(this::toScopeDTO)
                .toList();

        List<OAuth2Scope> previouslyApprovedList = previouslyApprovedScopes.stream()
                .map(this::toScopeDTO)
                .toList();

        return new ScopeList(scopesToApproveList, previouslyApprovedList);
    }

    public record ScopeList(List<OAuth2Scope> unChoseScopeList, List<OAuth2Scope> choseScopeList) {}


    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private OAuth2Scope toScopeDTO(String scope) {
        return switch (scope) {
            case "profile"      -> new OAuth2Scope("profile", "查看你的基本个人信息（昵称、头像、性别等）");
            case "email"        -> new OAuth2Scope("email", "查看并使用你的邮箱地址");
            case "phone"        -> new OAuth2Scope("phone", "查看你的手机号码");
            case "address"      -> new OAuth2Scope("address", "查看你的收货地址");
            case "message.read" -> new OAuth2Scope("message.read", "读取你的消息");
            case "message.write"-> new OAuth2Scope("message.write", "发送消息");
            default             -> new OAuth2Scope(scope, scope + " 权限");
        };
    }


}
