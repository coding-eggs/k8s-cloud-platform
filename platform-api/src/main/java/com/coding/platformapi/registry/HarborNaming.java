package com.coding.platformapi.registry;

/** Harbor 侧的名字规则（项目 / 机器人 / 平台凭据名）。纯函数，便于单测。 */
public final class HarborNaming {

    /** 平台共享项目（管理员流水线用） */
    public static final String PLATFORM_PROJECT = "platform";

    /** 机器人账号名（Harbor 会加前缀：robot$<project>+ci） */
    public static final String ROBOT_NAME = "ci";

    private static final int CREDENTIAL_NAME_MAX = 64;

    private HarborNaming() {
    }

    /** 租户项目：serviceAccount 已全局唯一且是小写 K8s 名 */
    public static String projectName(String serviceAccount) {
        return "t-" + serviceAccount;
    }

    public static String robotNameInHarbor(String projectName) {
        return "robot$" + projectName + "+" + ROBOT_NAME;
    }

    /**
     * 凭据行名：`Harbor <project>@<clusterId>`。带 clusterId 是因为**同名项目可能出现在多个集群**，
     * 而 `platform_credential.name` 全局唯一。
     *
     * <p>超长时裁项目名并补 hash：`Harbor <前缀>~<hash>@<clusterId>`（总长 64）。
     */
    public static String credentialName(String projectName, String clusterId) {
        String base = "Harbor " + projectName + "@" + clusterId;
        if (base.length() <= CREDENTIAL_NAME_MAX) {
            return base;
        }
        String hash = Integer.toHexString(Math.abs(projectName.hashCode()));
        int keep = CREDENTIAL_NAME_MAX - "Harbor ".length() - 1 - hash.length() - 1 - clusterId.length();
        int end = Math.max(1, Math.min(projectName.length(), keep));
        return "Harbor " + projectName.substring(0, end) + "~" + hash + "@" + clusterId;
    }
}
