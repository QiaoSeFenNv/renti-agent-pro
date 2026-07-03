package com.renti.agent.modules.notification.application.dto;

/** 公告创建/更新请求。published 兼容 bool/字符串（"1"/"true"/"yes"/"on"），缺省 true */
public record NotificationRequest(String title, String body, String tone, Object published) {
}
