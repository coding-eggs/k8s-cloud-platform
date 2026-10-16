package com.coding.auth.service;

/**
 * 一次自助账号操作的结果。
 *
 * @param sessionsKilled  踢掉了几个会话（索引里没有这个人的会话时是 0 —— 那是正常的，不是失败）
 * @param reloginRequired 调用方要不要把用户送回登录页。改密码是 {@code false}（当前这台留着），
 *                        改资料是 {@code true}（昵称/邮箱在 token 里，不重登就一直显示旧值）
 */
public record AccountChangeResult(int sessionsKilled, boolean reloginRequired) {
}
