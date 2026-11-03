package com.isd.ai.admin;

import com.isd.ai.admin.AiAdminDtos.*;
import com.isd.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ai")
@Tag(name="AI 配置管理",description="仅管理员：模型配置、版本切换和官方余额")
public class AiAdminController {
    private final AiConfigurationService configurations;
    private final AiBalanceService balances;
    public AiAdminController(AiConfigurationService configurations,AiBalanceService balances) {
        this.configurations=configurations;this.balances=balances;
    }
    @GetMapping("/configurations") public ResponseEntity<ApiResponse<List<ConfigResponse>>> list() {
        return result(configurations.list());
    }
    @PostMapping("/configurations") public ResponseEntity<ApiResponse<ConfigResponse>> create(@Valid @RequestBody SaveRequest input) {
        return result(configurations.create(input));
    }
    @PutMapping("/configurations/{id}") public ResponseEntity<ApiResponse<ConfigResponse>> update(@PathVariable long id,@Valid @RequestBody SaveRequest input) {
        return result(configurations.update(id,input));
    }
    @GetMapping("/configurations/{id}/versions") public ResponseEntity<ApiResponse<List<ConfigResponse>>> versions(@PathVariable long id) {
        return result(configurations.versions(id));
    }
    @PostMapping("/configurations/{id}/test") public ResponseEntity<ApiResponse<ConfigResponse>> test(@PathVariable long id,@Valid @RequestBody VersionRequest input) {
        return result(configurations.test(id,input.version()));
    }
    @PostMapping("/configurations/{id}/archive") public ResponseEntity<ApiResponse<Void>> archive(@PathVariable long id,@Valid @RequestBody VersionRequest input) {
        configurations.archive(id,input.version());return result(null);
    }
    @GetMapping("/runtime") public ResponseEntity<ApiResponse<RuntimeResponse>> runtime() { return result(configurations.runtime()); }
    @PutMapping("/runtime") public ResponseEntity<ApiResponse<RuntimeResponse>> runtime(@Valid @RequestBody RuntimeRequest input) {
        return result(configurations.setRuntime(input));
    }
    @GetMapping("/configurations/{id}/balance") public ResponseEntity<ApiResponse<BalanceResponse>> balance(@PathVariable long id) {
        return result(balances.balance(id));
    }
    @PostMapping("/configurations/{id}/balance/refresh") public ResponseEntity<ApiResponse<BalanceResponse>> refresh(@PathVariable long id) {
        return result(balances.refresh(id));
    }
    private <T> ResponseEntity<ApiResponse<T>> result(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
