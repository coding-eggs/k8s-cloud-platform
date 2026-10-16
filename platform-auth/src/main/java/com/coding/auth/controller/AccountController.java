package com.coding.auth.controller;

import com.coding.auth.service.AccountChangeResult;
import com.coding.auth.service.AccountService;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * **自助**账号端点：改自己的密码、改自己的资料。
 *
 * <p>⚠️ 路径刻意挂在 {@code /user/**} 下（与 {@code /user/login}、{@code /user/me} 同族），
 * 但**不能**进 {@code ignore-urls} —— 这两个都得带着有效的 HttpOnly 会话才让调。
 * 与 {@code /user/login} 不冲突：那条是表单登录的 {@code loginProcessingUrl}，
 * 由 {@code UsernamePasswordAuthenticationFilter} 精确匹配，不进控制器。
 *
 * <p>⚠️ 会话从**请求**里取（{@code HttpSession}），不是从 token 里 ——
 * 这里的根凭证就是那个 Redis 会话（见 {@code SessionController} 的说明）。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/user")
@Tag(name = "我的账号", description = "自助改密码 / 改资料（改**自己**的；管理端改别人在 platform-api）")
public class AccountController {

    private final AccountService account;

    @PostMapping("/password")
    @Operation(summary = "改我自己的密码",
            description = "必带原密码。成功后**踢掉我名下其它设备的会话**，当前这台保留（不用重登）。"
                    + "⚠️ 已签发的 access token 是自包含 JWT，最长还能再用 2 小时 —— 这只是「续不了期」。")
    public ResponseData<AccountChangeResult> changePassword(@RequestBody PasswordRequest req,
                                                            HttpServletRequest request) {
        return new ResponseData<>(account.changePassword(currentUsername(),
                req.oldPassword(), req.newPassword(), currentSessionId(request)));
    }

    @PostMapping("/profile")
    @Operation(summary = "改我自己的资料（昵称 / 邮箱）",
            description = "昵称与邮箱留空 = 清掉（界面回落到用户名）。成功后**全部会话都踢掉**"
                    + "（含当前这台），调用方要回登录页 —— 这两个字段在 token 里，不重登就一直显示旧值。")
    public ResponseData<AccountChangeResult> updateProfile(@RequestBody ProfileRequest req,
                                                           HttpServletRequest request) {
        return new ResponseData<>(account.updateProfile(currentUsername(),
                req.displayName(), req.email(), currentSessionId(request)));
    }

    /** 当前登录用户名 —— 就是 {@code SecurityUser} 的 username（Redis 会话索引也是按它建的）。 */
    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new CloudPlatformException(EnumResponseType.USER_UN_LOGIN);
        }
        return auth.getName();
    }

    /**
     * 当前这台设备的会话 id —— 改密码时不踢它。
     * 取不到（理论上有会话才进得来）就返回 null = 谁都别留，宁可多踢也别漏踢。
     */
    private static String currentSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : session.getId();
    }

    /** @param oldPassword 原密码（必填，用来确认是本人） */
    public record PasswordRequest(String oldPassword, String newPassword) {
    }

    /** @param displayName 留空 = 清掉；@param email 留空 = 清掉 */
    public record ProfileRequest(String displayName, String email) {
    }
}
