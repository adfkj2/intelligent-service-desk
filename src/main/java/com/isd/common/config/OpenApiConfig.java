package com.isd.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档元信息。
 * 访问 /swagger-ui.html 直接调接口；本项目用 Session 认证，
 * 在 Swagger UI 里先调用 POST /api/auth/login，浏览器会自动带上 JSESSIONID，
 * 因此不需要 security scheme 的 Bearer 配置（这是 Session 方案的一个隐性便利）。
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "ISD 智能服务台 API",
                version = "v1",
                description = "工单全流程（状态机 + 乐观锁）、知识库、Session 认证与三角色权限。"
                        + "统一响应结构：{code, message, data}，HTTP 状态码保留原有语义。"))
public class OpenApiConfig {
}
