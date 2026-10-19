package com.coding.platformapi.models;

/** 规则编辑页的收件人候选。email 可能为空（用户没填） */
public record NotificationUserView(String id, String displayName, String email) {
}
