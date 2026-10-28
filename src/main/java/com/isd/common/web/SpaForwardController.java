package com.isd.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端路由回退（SPA fallback）。
 *
 * 为什么需要它：Vue Router 使用 history 模式，用户直接刷新 /tickets/12 时，
 * 请求会先到 Spring，此时没有任何 Controller 匹配，若不回退就会拿到 404。
 *
 * 为什么用显式白名单而不是通配 {@code /**}：
 * 1. 通配会把 /api/**、/v3/api-docs、/swagger-ui 一起吞掉，把"接口不存在"变成"返回一段 HTML"；
 * 2. 错链仍然应该是 404（NoResourceFoundException），不能被掩盖成 200 的首页。
 *
 * 注意：这里只做 forward，不返回内容；真正的 index.html 由静态资源处理器提供
 * （构建产物在 classpath:/static/index.html）。
 */
@Controller
public class SpaForwardController {

    @GetMapping({
            "/",
            "/login",
            "/change-password",
            "/tickets",
            "/tickets/{id}",
            "/kb",
            "/admin/users"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
