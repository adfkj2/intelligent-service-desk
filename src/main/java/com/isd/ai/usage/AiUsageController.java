package com.isd.ai.usage;

import com.isd.auth.domain.UserRole;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.result.ApiResponse;
import com.isd.common.result.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai/usage")
public class AiUsageController {
    private final AiUsageService usage;
    private final CurrentUserProvider users;

    public AiUsageController(AiUsageService usage, CurrentUserProvider users) { this.usage = usage; this.users = users; }

    @GetMapping("/summary")
    public ApiResponse<AiUsageResponses.Summary> summary(@RequestParam Map<String, String> parameters, HttpServletResponse response) {
        requireAdmin(response); return ApiResponse.ok(usage.summary(parameters));
    }

    @GetMapping("/daily")
    public ApiResponse<List<AiUsageResponses.Daily>> daily(@RequestParam Map<String, String> parameters, HttpServletResponse response) {
        requireAdmin(response); return ApiResponse.ok(usage.daily(parameters));
    }

    @GetMapping("/users")
    public ApiResponse<List<AiUsageResponses.User>> users(@RequestParam Map<String, String> parameters, HttpServletResponse response) {
        requireAdmin(response); return ApiResponse.ok(usage.users(parameters));
    }

    @GetMapping("/calls")
    public ApiResponse<PageResponse<AiUsageResponses.Call>> calls(@RequestParam Map<String, String> parameters, HttpServletResponse response) {
        requireAdmin(response); return ApiResponse.ok(usage.calls(parameters));
    }

    private void requireAdmin(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (users.require().getRole() != UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}
