package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * MySQL 命名锁（GET_LOCK）。锁绑定在一条<b>独占</b>连接上，body 里的 mapper 调用走别的连接（自动提交），
 * 所以 body 内的读看得到其他实例已提交的分配、body 内的释放在放锁前就已提交。
 * 锁名 = {@code ingress:} + sha1(集群/命名空间)，不超过 MySQL 的 64 字符上限。
 */
@Component
public class MysqlIngressNamespaceLock implements IngressNamespaceLock {

    private static final int TIMEOUT_SECONDS = 30;

    private final DataSource dataSource;

    public MysqlIngressNamespaceLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String clusterId, String namespace, Runnable body) {
        String name = lockName(clusterId, namespace);
        try (Connection c = dataSource.getConnection()) {
            if (!acquire(c, name)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "命名空间 " + namespace + " 的网关端口正在被其他发布同步，请稍后重试");
            }
            try {
                body.run();
            } finally {
                try (PreparedStatement ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    ps.setString(1, name);
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "获取网关端口同步锁失败：" + e.getMessage());
        }
    }

    private static boolean acquire(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, name);
            ps.setInt(2, TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    static String lockName(String clusterId, String namespace) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1")
                    .digest((clusterId + "/" + namespace).getBytes(StandardCharsets.UTF_8));
            return "ingress:" + HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
