package com.coding.k8sserver.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeaweedS3ConfigObjectsTest {

    @Test
    void adminOnlyProducesTheIdentityTheS3GatewayExpects() {
        String json = SeaweedS3ConfigObjects.adminOnly("AK1", "SK1");
        String[] cred = SeaweedS3ConfigObjects.findCredentials(json, SeaweedS3ConfigObjects.ADMIN_IDENTITY);
        assertThat(cred).containsExactly("AK1", "SK1");
        assertThat(json).contains("\"actions\":").contains("Admin");
    }

    @Test
    void upsertReplacesTheSameIdentityInsteadOfDuplicating() {
        // identity 名唯一：重新发凭据走的是 upsert，不是往文件里追加同名条目
        String json = SeaweedS3ConfigObjects.adminOnly("AK1", "SK1");
        json = SeaweedS3ConfigObjects.upsertIdentity(json, "bucket-a", "AK2", "SK2",
                List.of("Read:bucket-a"));
        json = SeaweedS3ConfigObjects.upsertIdentity(json, "bucket-a", "AK3", "SK3",
                List.of("Write:bucket-a"));

        assertThat(SeaweedS3ConfigObjects.findCredentials(json, "bucket-a"))
                .containsExactly("AK3", "SK3");
        assertThat(SeaweedS3ConfigObjects.findCredentials(json, SeaweedS3ConfigObjects.ADMIN_IDENTITY))
                .containsExactly("AK1", "SK1");
        // 只剩两个身份：admin + bucket-a
        assertThat(json.split("\"name\":", -1).length - 1).isEqualTo(2);
    }

    @Test
    void removeIdentityOnlyDropsThatOne() {
        String json = SeaweedS3ConfigObjects.upsertIdentity(
                SeaweedS3ConfigObjects.adminOnly("AK1", "SK1"), "bucket-a", "AK2", "SK2",
                List.of("Read:bucket-a"));
        String after = SeaweedS3ConfigObjects.removeIdentity(json, "bucket-a");
        assertThat(SeaweedS3ConfigObjects.findCredentials(after, "bucket-a")).isNull();
        assertThat(SeaweedS3ConfigObjects.findCredentials(after, SeaweedS3ConfigObjects.ADMIN_IDENTITY))
                .isNotNull();
    }

    @Test
    void randomKeysLookLikeS3Credentials() {
        String ak = SeaweedS3ConfigObjects.randomAccessKey();
        String sk = SeaweedS3ConfigObjects.randomSecretKey();
        assertThat(ak).hasSize(20).matches("[A-Z0-9]+");
        assertThat(sk).hasSizeGreaterThanOrEqualTo(32);
        assertThat(SeaweedS3ConfigObjects.randomAccessKey()).isNotEqualTo(ak);
    }

    @Test
    void identityNameCarriesTheBucketForOperatorReadability() {
        assertThat(SeaweedS3ConfigObjects.identityForBucket("t-ab12-logs", "deadbeef"))
                .isEqualTo("bucket-t-ab12-logs-deadbeef");
    }

    @Test
    void blankConfigStartsFromEmptyIdentityList() {
        assertThat(SeaweedS3ConfigObjects.parse(null).get("identities")).isInstanceOf(java.util.List.class);
        assertThat(SeaweedS3ConfigObjects.findCredentials("", "nobody")).isNull();
    }

    @Test
    void anonymousReadIsScopedToTheBucketAndKeepsOtherIdentities() {
        String json = SeaweedS3ConfigObjects.adminOnly("AK1", "SK1");
        json = SeaweedS3ConfigObjects.upsertIdentity(json, "bucket-t-a", "AK2", "SK2",
                List.of("Read:t-a", "Write:t-a"));

        json = SeaweedS3ConfigObjects.setAnonymousRead(json, "t-a", true);
        json = SeaweedS3ConfigObjects.setAnonymousRead(json, "t-b", true);

        // 两个桶都匿名可读，且原有身份一个不少
        assertThat(SeaweedS3ConfigObjects.anonymousReadBuckets(json))
                .containsExactlyInAnyOrder("t-a", "t-b");
        assertThat(SeaweedS3ConfigObjects.findCredentials(json, SeaweedS3ConfigObjects.ADMIN_IDENTITY))
                .containsExactly("AK1", "SK1");
        assertThat(SeaweedS3ConfigObjects.findCredentials(json, "bucket-t-a"))
                .containsExactly("AK2", "SK2");
        // 匿名身份没有 credentials —— 这正是"不带签名也能读"的机制
        assertThat(SeaweedS3ConfigObjects.findCredentials(json,
                SeaweedS3ConfigObjects.ANONYMOUS_IDENTITY)).isNull();
    }

    @Test
    void turningAnonymousReadOffOnlyDropsThatBucket() {
        String json = SeaweedS3ConfigObjects.adminOnly("AK1", "SK1");
        json = SeaweedS3ConfigObjects.setAnonymousRead(json, "t-a", true);
        json = SeaweedS3ConfigObjects.setAnonymousRead(json, "t-b", true);

        json = SeaweedS3ConfigObjects.setAnonymousRead(json, "t-a", false);

        assertThat(SeaweedS3ConfigObjects.anonymousReadBuckets(json)).containsExactly("t-b");

        json = SeaweedS3ConfigObjects.setAnonymousRead(json, "t-b", false);
        // 没有 action 了 → 整条匿名身份删掉，配置保持干净
        assertThat(SeaweedS3ConfigObjects.anonymousReadBuckets(json)).isEmpty();
        assertThat(json).doesNotContain("\"anonymous\"");
    }

    @Test
    void turningAnonymousReadOffAlsoStripsAHandAddedListAction() {
        String json = SeaweedS3ConfigObjects.adminOnly("AK1", "SK1");
        json = SeaweedS3ConfigObjects.setAnonymousRead(json, "t-a", true);
        // 模拟有人手工加了 List：关闭时一起摘掉，别留"能列目录"的后门
        json = json.replace("\"Read:t-a\"", "\"Read:t-a\",\"List:t-a\"");
        assertThat(json).contains("List:t-a");

        json = SeaweedS3ConfigObjects.setAnonymousRead(json, "t-a", false);

        assertThat(json).doesNotContain("List:t-a").doesNotContain("Read:t-a");
    }
}
