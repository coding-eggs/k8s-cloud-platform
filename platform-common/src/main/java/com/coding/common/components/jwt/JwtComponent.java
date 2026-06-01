package com.coding.common.components.jwt;

import com.coding.common.components.jwt.impl.JweTokenStrategy;
import com.coding.common.components.jwt.impl.JwsTokenStrategy;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;

@Slf4j
@Component
@ConditionalOnBean(value = JwtProperties.class)
@RequiredArgsConstructor
public class JwtComponent<T> {

    private final JwtProperties jwtProperties;
    private final JwsTokenStrategy<T> jwsTokenStrategy;
    private final JweTokenStrategy<T> jweTokenStrategy;

    /**
     * 获取当前策略
     */
    private JwtStrategy<T> getCurrentStrategy() {
        return "JWE".equalsIgnoreCase(jwtProperties.getType())
                ? jweTokenStrategy
                : jwsTokenStrategy;
    }

    /**
     * 生成访问令牌
     */
    public String generateAccessToken(T t, String tokenType, long expiration, String subject ) throws NoSuchAlgorithmException, InvalidKeySpecException, JOSEException, ParseException {
        return getCurrentStrategy().generateToken(t, tokenType, expiration, subject);
    }

    /**
     * 验证并解析令牌
     */
    public T parseToken(String token , Class<T> clazz) throws ParseException, NoSuchAlgorithmException, InvalidKeySpecException, JOSEException {
        if (!StringUtils.hasText(token)) {
            throw new JOSEException("令牌不能为空");
        }
        return getCurrentStrategy().parseToken(removeTokenPrefix(token), clazz);
    }

    /**
     * 验证令牌是否有效
     */
    public boolean validateToken(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        return getCurrentStrategy().validateToken(removeTokenPrefix(token));
    }

    /**
     * 获取令牌剩余有效期（秒）
     */
    public long getRemainingSeconds(String token) {
        if (!StringUtils.hasText(token)) {
            return 0;
        }
        return getCurrentStrategy().getRemainingSeconds(removeTokenPrefix(token));
    }

    /**
     * 获取令牌ID（用于防重放）
     */
    public String getTokenId(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        return getCurrentStrategy().getTokenId(removeTokenPrefix(token));
    }


    /**
     * 移除令牌前缀（如 "Bearer "）
     */
    private String removeTokenPrefix(String token) {
        String prefix = jwtProperties.getTokenPrefix();
        if (StringUtils.hasText(prefix) && token.startsWith(prefix)) {
            return token.substring(prefix.length()).trim();
        }
        return token.trim();
    }

    /**
     * 添加令牌前缀
     */
    public String addTokenPrefix(String token) {
        return jwtProperties.getTokenPrefix() + token;
    }

}
