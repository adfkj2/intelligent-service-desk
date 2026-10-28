package com.isd.common.result;

import java.util.List;

/**
 * 分页响应。
 * 为什么不直接把 MyBatis 的 Page 对象返回：分页是接口契约的一部分，
 * 自己定义结构可以控制字段（page/size/total/totalPages/items），换 ORM 不影响前端。
 *
 * @param page       当前页码，从 1 开始
 * @param size       每页条数
 * @param total      满足条件的总条数
 * @param totalPages 总页数
 * @param items      当前页数据
 */
public record PageResponse<T>(int page, int size, long total, int totalPages, List<T> items) {

    public static <T> PageResponse<T> of(int page, int size, long total, List<T> items) {
        int totalPages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(page, size, total, totalPages, items);
    }
}
