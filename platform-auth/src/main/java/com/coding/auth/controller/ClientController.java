package com.coding.auth.controller;

import com.coding.auth.client.OAuth2Scope;
import com.coding.auth.client.RegisteredClientReq;
import com.coding.auth.client.RegisteredClientRes;
import com.coding.auth.service.ClientService;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/clients")
@Tag(name = "OAuth2 客户端管理", description = "RegisteredClient CRUD")
public class ClientController {

    private final ClientService clientService;

    @PostMapping(value = "/create")
    @Operation(summary = "创建客户端")
    public ResponseData<RegisteredClientRes> create(@RequestBody RegisteredClientReq req) {
        return new ResponseData<>(clientService.create(req));
    }

    @PostMapping("/update")
    @Operation(summary = "更新客户端")
    public ResponseData<Boolean> update(@RequestBody RegisteredClientReq req) {
        clientService.update(req);
        return new ResponseData<>(EnumResponseType.SUCCESS, true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除客户端")
    public ResponseData<Void> delete(@RequestParam("id") String id) {
        clientService.delete(id);
        return new ResponseData<>();
    }

    @GetMapping("/getById")
    @Operation(summary = "查询单个客户端")
    public ResponseData<RegisteredClientRes> getById(@RequestParam String id) {
        return new ResponseData<>(clientService.getById(id));
    }

    @GetMapping("/list")
    @Operation(summary = "查询所有客户端列表")
    public ResponseData<List<RegisteredClientRes>> listAll() {
        return new ResponseData<>(clientService.listAll());
    }

    @GetMapping("/scope-list")
    @Operation(summary = "获取可选 scope 列表（含描述）")
    public ResponseData<List<OAuth2Scope>> scopeList() {
        List<OAuth2Scope> scopes = List.of(
                new OAuth2Scope(OidcScopes.OPENID,  "认证用户身份并获取登录状态"),
                new OAuth2Scope(OidcScopes.PROFILE, "查看你的基本个人信息（昵称、头像、性别等）"),
                new OAuth2Scope(OidcScopes.EMAIL,   "查看并使用你的邮箱地址"),
                new OAuth2Scope(OidcScopes.PHONE,   "查看你的手机号码"),
                new OAuth2Scope(OidcScopes.ADDRESS,  "查看你的收货地址"),
                new OAuth2Scope("message.read",     "读取你的消息"),
                new OAuth2Scope("message.write",    "发送消息")
        );
        return new ResponseData<>(scopes);
    }
}
