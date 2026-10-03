package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限点行单行写事务：每方法一条语句，返回即提交。
 * 独立 bean 让 {@code @Transactional} 走代理 —— 调用方（PermissionService）在这些方法
 * <b>返回后</b>（= 提交后）才重读 DB 并热换运行时规则表，提交失败则运行时不受影响（DB 与运行时不漂移）。
 */
@Service
@RequiredArgsConstructor
class PermissionRowWriter {

    private final PlatformPermissionMapper permMapper;

    @Transactional
    void insert(PlatformPermission row) {
        permMapper.insert(row);
    }

    @Transactional
    void update(PlatformPermission row) {
        permMapper.updateByPrimaryKeySelective(row);
    }

    @Transactional
    void softDelete(String id) {
        permMapper.softDeleteById(id);
    }
}
