package com.coding.auth.service;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.models.auth.PlatformUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;

/**
 * **自助**账号操作：改自己的密码、改自己的资料。
 *
 * <h2>⚠️ 为什么它在 platform-auth 而不是 platform-api</h2>
 * 这两件事都**必须动会话**：改密码要把别的设备踢下去，改资料要连当前这台一起请出去
 * （见下面 {@link #updateProfile} 的说明）。会话是 Redis 里的 Spring Session，只有 auth-server 摸得到。
 * 用户表它本来就在读（登录要查），所以放这儿比"platform-api 改库 + 反手调 auth-server 踢会话"少一跳。
 * platform-api 的 {@code UserService} 保持**管理端**角色（建人、授角色），两边不重叠。
 *
 * <h2>⚠️ 踢会话踢到什么程度（说实话）</h2>
 * 删会话 = 那台设备**续不了期**。但它手上那张 access token 是**自包含 JWT**
 * （{@code platform-web-client} 配的是 2 小时），所以**最长还能再用 2 小时**。
 * 要"立刻断"得在每请求路径上加"凭证版本"校验（platform-api 与 k8s-server 都得动）—— 那是另一件事。
 * 页面上要把这句话写给用户看，别让人以为点了就"对方立刻掉线"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    /**
     * 新密码的最短长度。**只在自助改密码这一条路上**（管理端建人那条没这个规则）——
     * 不顺手给建人也加上，是因为那会改掉现有行为，属于另一个决定。
     */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** 与库里的列对齐（{@code display_name varchar(64)}、{@code email varchar(128)}）。 */
    private static final int MAX_DISPLAY_NAME = 64;
    private static final int MAX_EMAIL = 128;

    private final PlatformUserMapper users;
    private final PasswordEncoder encoder;

    /**
     * {@code RedisIndexedSessionRepository} —— 它能**按用户名**找出这个人的所有会话。
     *
     * <p>⚠️ 索引是**仓库自己在 save() 时**写的（默认的 {@code PrincipalNameIndexResolver} 从会话里的
     * {@code SPRING_SECURITY_CONTEXT} 取 {@code authentication.name}），**不是**登录策略写的。
     * 开关是 {@code RedisSessionConfig} 上的 {@code @EnableRedisIndexedHttpSession} ——
     * 用 {@code @EnableRedisHttpSession} 的话连索引机制都没有，而这个差别**不报错、只是静默地
     * 一个都踢不动**。
     *
     * <p>⚠️ <b>什么时候才写索引</b>（这条最容易想错 —— 本类第一版注释就写错了，害得排查时照着它推）：
     * **只有"这次改动里含 security context"的那一次 save 才写**。判据在
     * {@code RedisIndexedSessionRepository.saveDelta()} 里，原话是
     * {@code if (delta.containsKey("sessionAttr:SPRING_SECURITY_CONTEXT"))}。由此三条推论：
     * <ul>
     *   <li>{@code /session/keepalive} 只改 {@code lastKeepaliveAt} → **一次都不会写索引**。
     *       所以"多用一会儿就补上了"是错的。</li>
     *   <li>本功能上线**之前**建的会话，它的 context 是旧构建写进去的 → 之后怎么 keepalive 都不会补；
     *       踢不到，会自然过期。**必须重新登录一次**才会进索引（登录会写 context）。</li>
     *   <li>从来没有过 security context 的会话（例如访问 {@code /oauth2/authorize} 时建的那个
     *       只带 {@code SPRING_SECURITY_SAVED_REQUEST} 的匿名会话）本来就不该进索引。</li>
     * </ul>
     *
     * <p>⚠️ 别拿 Redis 的 {@code EXISTS} / {@code KEYS} 当"踢没踢掉"的判据：{@code deleteById} 之后
     * 键可能**还留着一个空壳**（{@code findById} 已经返回 null，哈希键等 TTL 自己收走）。
     * 要看的是 {@code findById} 与索引集合。
     *
     * <p>⚠️ 注入的是**具体类型**而不是 {@code FindByIndexNameSessionRepository<? extends Session>}：
     * 那个 bean 的泛型参数是 {@code RedisIndexedSessionRepository.RedisSession}，
     * **Spring 不认通配符那条**，按接口注入直接启动失败
     * （{@code No qualifying bean of type 'FindByIndexNameSessionRepository<Session>'}）。
     * 实现只有一个，按具体类型注入反而更直白。
     */
    private final RedisIndexedSessionRepository sessions;

    /**
     * 改密码：验旧密码 → 存新哈希 → 踢掉**其它**会话，当前这台留着。
     *
     * @param currentSessionId 当前这台设备的会话 id（不踢它）—— 密码不在 token 里，
     *                         没有"必须重登"的理由，把人自己踢下线纯属添乱
     * @return 踢掉了几个会话；{@code reloginRequired} 恒为 false
     */
    public AccountChangeResult changePassword(String username, String oldPassword, String newPassword,
                                              String currentSessionId) {
        PlatformUser u = require(username);
        if (oldPassword == null || oldPassword.isEmpty() || !encoder.matches(oldPassword, u.getPassword())) {
            // ⚠️ 拒绝就要真拒绝：一个字都不改、一个会话都不踢（测试钉着）
            throw invalid("原密码不对");
        }
        String next = newPassword == null ? "" : newPassword;
        if (next.length() < MIN_PASSWORD_LENGTH) {
            throw invalid("新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (encoder.matches(next, u.getPassword())) {
            throw invalid("新密码不能和原密码相同");
        }
        u.setPassword(encoder.encode(next));
        u.setUpdatedAt(new Date());
        // ⚠️ 用**整行**更新（不是 selective）：对象是刚从库里读出来的，字段齐全；
        //    而"把昵称/邮箱清空"这件事只能是整行写 —— selective 会把 null 当"不动"跳过。
        users.updateByPrimaryKey(u);

        int killed = killSessions(username, currentSessionId);
        log.info("用户 {} 改了密码：新哈希已落库，踢掉 {} 个其它会话（当前会话保留）", username, killed);
        return new AccountChangeResult(killed, false);
    }

    /**
     * 改资料：写库 → 踢掉**全部**会话（含当前这台）。
     *
     * <p>⚠️ 为什么跟改密码不一样：{@code displayName} / {@code email} 是装在 **token 的 data claim**
     * 里的，而 token 是自包含的（2 小时）。不重新登录，顶栏就一直显示旧昵称 ——
     * 与其做一个"改了但看起来没改"的按钮，不如当场把人请出去，重新登录即拿到新值。
     *
     * @return 踢掉了几个会话；{@code reloginRequired} 恒为 true（调用方据此回登录页）
     */
    public AccountChangeResult updateProfile(String username, String displayName, String email,
                                             String currentSessionId) {
        PlatformUser u = require(username);
        String name = trimToNull(displayName);
        String mail = trimToNull(email);
        if (name != null && name.length() > MAX_DISPLAY_NAME) {
            throw invalid("昵称最长 " + MAX_DISPLAY_NAME + " 个字符");
        }
        if (mail != null && mail.length() > MAX_EMAIL) {
            throw invalid("邮箱最长 " + MAX_EMAIL + " 个字符");
        }
        u.setDisplayName(name);
        u.setEmail(mail);
        u.setUpdatedAt(new Date());
        users.updateByPrimaryKey(u);

        // keepSessionId 传 null = 谁都别留
        int killed = killSessions(username, null);
        log.info("用户 {} 改了资料：踢掉全部 {} 个会话，要求重新登录", username, killed);
        return new AccountChangeResult(killed, true);
    }

    private PlatformUser require(String username) {
        PlatformUser u = username == null ? null : users.selectByUsername(username);
        if (u == null) {
            throw invalid("用户不存在");
        }
        return u;
    }

    /**
     * 按用户名踢会话。{@code keepSessionId} 为 null 表示"谁都别留"。
     *
     * <p>索引里没有这个人的会话（老会话 / 从没登录过）时返回 0 —— **不报错**：
     * "踢不到"和"踢失败"是两回事，前者是正常的。
     */
    private int killSessions(String principalName, String keepSessionId) {
        Map<String, RedisIndexedSessionRepository.RedisSession> found =
                sessions.findByPrincipalName(principalName);
        if (found == null || found.isEmpty()) {
            return 0;
        }
        int killed = 0;
        for (RedisIndexedSessionRepository.RedisSession s : found.values()) {
            if (s == null || s.getId() == null) {
                continue;
            }
            if (keepSessionId != null && keepSessionId.equals(s.getId())) {
                continue;
            }
            sessions.deleteById(s.getId());
            killed++;
        }
        return killed;
    }

    private static String trimToNull(String raw) {
        String v = raw == null ? "" : raw.trim();
        return v.isEmpty() ? null : v;
    }

    private static CloudPlatformException invalid(String message) {
        return new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, message);
    }
}
