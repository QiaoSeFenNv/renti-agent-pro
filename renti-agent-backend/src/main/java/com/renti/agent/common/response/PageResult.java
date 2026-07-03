package com.renti.agent.common.response;

import java.util.List;

/**
 * 统一分页响应结构。
 *
 * @param items      当前页数据
 * @param total      总记录数
 * @param page       当前页码（从 1 开始）
 * @param pageSize   每页大小
 * @param totalPages 总页数
 * @param <T>        条目类型
 */
public record PageResult<T>(List<T> items, long total, int page, int pageSize, int totalPages) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int pageSize) {
        int totalPages = pageSize > 0 ? (int) Math.max(1, (total + pageSize - 1) / pageSize) : 1;
        return new PageResult<>(items, total, page, pageSize, totalPages);
    }
}
