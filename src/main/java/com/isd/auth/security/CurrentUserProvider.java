package com.isd.auth.security;

import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户获取入口（全项目唯一入口）。
 *
 * 设计要点（也是面试要点）：身份永远从 SecurityContext 取，绝不由前端传入 userId 参数。
 * 否则攻击者只要把请求体里的 userId 改成别人的，就能越权；AI 场景下更是能通过提示注入伪造身份。
 */
@Component
public class CurrentUserProvider {

    public LoginUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或会话已失效");
        }
        if (!(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录信息无效，请重新登录");
        }
        return loginUser;
    }

    public Long requireId() {
        return require().getId();
    }
}
