package com.coding.platformapi.models.oauthclient;

/** 外部客户端类型，创建后不可改 */
public enum ClientType {
    /** 有后端、能认证自己：client_secret_basic / client_secret_post / private_key_jwt（可兼带 none） */
    CONFIDENTIAL,
    /** 纯前端 / 移动端：none，授权码（强制 PKCE）+ 会话续期 */
    PUBLIC
}
