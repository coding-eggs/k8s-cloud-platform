package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppServiceDesiredTest {

    private static Map<String, Object> with(String name, String value) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("env", List.of(Map.of("name", name, "kind", "LITERAL", "value", value)));
        return d;
    }

    @Test
    void benignNamesThatContainSensitiveWordsDoNotWarn() {
        assertThat(AppServiceDesired.warnings(with("TOKEN_TTL", "3600"))).isEmpty();
        assertThat(AppServiceDesired.warnings(with("MAX_TOKENS", "4096"))).isEmpty();
        assertThat(AppServiceDesired.warnings(with("PASSWORD_MIN_LENGTH", "8"))).isEmpty();
    }

    @Test
    void plainTextCredentialWarns() {
        assertThat(AppServiceDesired.warnings(with("DB_PASSWORD", "hunter2"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("DB_PASSWORD", "hunter2${api.host}"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("STRIPE_API_KEY", "sk_live_x"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("AUTH_TOKEN", "abc"))).hasSize(1);
        assertThat(AppServiceDesired.warnings(with("APP_SECRET", "abc"))).hasSize(1);
    }

    @Test
    void pureReferenceDoesNotWarn() {
        assertThat(AppServiceDesired.warnings(with("DB_PASSWORD", "${db.password}"))).isEmpty();
    }

    @Test
    void underscoreOnlyNamesDoNotThrow() {
        assertThat(AppServiceDesired.warnings(with("_", "x"))).isEmpty();
        assertThat(AppServiceDesired.warnings(with("__", "x"))).isEmpty();
    }
}
