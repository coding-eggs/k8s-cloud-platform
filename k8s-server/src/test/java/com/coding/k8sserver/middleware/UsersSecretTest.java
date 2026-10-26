package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class UsersSecretTest {

    @Test
    void secretNameIsDerivedFromInstanceName() {
        assertThat(UsersSecret.name("mw-mysql-abcdefgh")).isEqualTo("mw-mysql-abcdefgh-users");
    }

    @Test
    void readsPasswordsIgnoringMissingAndBlankKeys() {
        Secret s = new SecretBuilder()
                .addToData("alice", b64("pw-alice"))
                .addToData("bob", "")
                .build();

        assertThat(UsersSecret.passwords(s))
                .containsEntry("alice", "pw-alice")
                .doesNotContainKey("bob");
        assertThat(UsersSecret.passwords(null)).isEmpty();
        assertThat(UsersSecret.passwords(new SecretBuilder().build())).isEmpty();
    }

    @Test
    void mergeKeepsOtherUsersAndOverwritesTheSameName() {
        var current = java.util.Map.of("alice", b64("old"), "bob", b64("pw-bob"));

        var merged = UsersSecret.merge(current, "alice", "new");

        assertThat(merged).containsEntry("alice", b64("new")).containsEntry("bob", b64("pw-bob"));
    }

    @Test
    void removeDropsOnlyThatKey() {
        var current = java.util.Map.of("alice", b64("a"), "bob", b64("b"));

        assertThat(UsersSecret.without(current, "alice")).containsOnlyKeys("bob");
    }

    private static String b64(String v) {
        return Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8));
    }
}
