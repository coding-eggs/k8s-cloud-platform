package com.coding.platformapi.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationDeployCredentialsTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ApplicationDeployCredentials credentials;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        credentials = new ApplicationDeployCredentials(redis);
    }

    private static String bearer(long expEpochSeconds) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"u\",\"exp\":" + expEpochSeconds + "}")
                .getBytes(StandardCharsets.UTF_8));
        return "Bearer " + header + "." + payload + ".sig";
    }

    @Test
    void ttlFollowsTokenExpiry() {
        long now = 1_000_000_000_000L;
        assertThat(ApplicationDeployCredentials.ttlOf(bearer(now / 1000 + 600), now))
                .isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void ttlFallsBackWhenNotAJwt() {
        assertThat(ApplicationDeployCredentials.ttlOf("Bearer opaque", 0))
                .isEqualTo(ApplicationDeployCredentials.FALLBACK_TTL);
        assertThat(ApplicationDeployCredentials.ttlOf("Bearer a.!!!.c", 0))
                .isEqualTo(ApplicationDeployCredentials.FALLBACK_TTL);
    }

    @Test
    void rememberWritesThroughToRedisWithTokenTtl() {
        String token = bearer(System.currentTimeMillis() / 1000 + 900);

        credentials.rememberExecution(7L, token);

        verify(values).set(eq("platform:app-release:cred:7"), eq(token), any(Duration.class));
        assertThat(credentials.getExecution(7L)).isEqualTo(token);
    }

    @Test
    void expiredTokenIsNotWrittenToRedis() {
        credentials.rememberExecution(7L, bearer(System.currentTimeMillis() / 1000 - 10));

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 接手的实例：本进程内存里没有，从 Redis 取 —— 这就是「发布自己」后能续跑的原因 */
    @Test
    void anotherInstanceReadsItFromRedis() {
        when(values.get("platform:app-release:cred:7")).thenReturn("Bearer shared");

        assertThat(credentials.getExecution(7L)).isEqualTo("Bearer shared");
    }

    @Test
    void forgetRemovesBothCopies() {
        credentials.rememberExecution(7L, "Bearer opaque");

        credentials.forgetExecution(7L);

        verify(redis).delete("platform:app-release:cred:7");
        assertThat(credentials.getExecution(7L)).isNull();
    }

    /** Redis 只是优化：不通时退化成仅内存，不抛 */
    @Test
    void redisFailureDegradesToMemoryOnly() {
        doThrow(new RedisConnectionFailureException("down")).when(values)
                .set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        credentials.rememberExecution(7L, "Bearer opaque");
        assertThat(credentials.getExecution(7L)).isEqualTo("Bearer opaque");
        assertThat(credentials.getExecution(8L)).isNull();
        credentials.forgetExecution(7L);
        assertThat(credentials.getExecution(7L)).isNull();
    }

    @Test
    void memoryOnlyConstructorNeverTouchesRedis() {
        ApplicationDeployCredentials local = new ApplicationDeployCredentials();
        local.rememberExecution(1L, "Bearer x");
        assertThat(local.getExecution(1L)).isEqualTo("Bearer x");
        assertThat(local.getExecution(2L)).isNull();
    }
}
