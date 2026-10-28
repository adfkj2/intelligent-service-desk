package com.isd.kb.controller;

import java.util.List;

import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.result.ApiResponse;
import com.isd.common.result.PageResponse;
import com.isd.kb.dto.CreateCategoryRequest;
import com.isd.kb.dto.CreateItemRequest;
import com.isd.kb.dto.KbCategoryResponse;
import com.isd.kb.dto.KbItemResponse;
import com.isd.kb.dto.KbItemSummary;
import com.isd.kb.dto.UpdateItemRequest;
import com.isd.kb.service.KbService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库接口：分类 + 条目 + 关键词检索。
 * URL 级规则只区分"登录"与"ADMIN 建分类"，更细的"只能改自己的条目"在 Service 里判断。
 */
@RestController
@RequestMapping("/api/kb")
@Tag(name = "知识库", description = "分类管理、条目 CRUD、关键词检索")
public class KbController {

    private final KbService kbService;
    private final CurrentUserProvider currentUserProvider;

    public KbController(KbService kbService, CurrentUserProvider currentUserProvider) {
        this.kbService = kbService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/categories")
    @Operation(summary = "分类列表", description = "登录用户均可访问")
    public ApiResponse<List<KbCategoryResponse>> listCategories() {
        return ApiResponse.ok(kbService.listCategories());
    }

    @PostMapping("/categories")
    @Operation(summary = "新建分类", description = "仅 ADMIN")
    public ResponseEntity<ApiResponse<KbCategoryResponse>> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {
        KbCategoryResponse created = kbService.createCategory(request, currentUserProvider.require());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping("/items")
    @Operation(summary = "条目分页列表", description = "可按分类过滤；列表不返回正文")
    public ApiResponse<PageResponse<KbItemSummary>> listItems(
            @Parameter(description = "分类 ID，可空") @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(kbService.listItems(categoryId, page, size));
    }

    @PostMapping("/items")
    @Operation(summary = "新建知识条目", description = "SUPPORT / ADMIN")
    public ResponseEntity<ApiResponse<KbItemResponse>> createItem(
            @Valid @RequestBody CreateItemRequest request) {
        KbItemResponse created = kbService.createItem(request, currentUserProvider.require());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping("/items/{id}")
    @Operation(summary = "条目详情")
    public ApiResponse<KbItemResponse> getItem(@PathVariable Long id) {
        return ApiResponse.ok(kbService.getItem(id));
    }

    @PutMapping("/items/{id}")
    @Operation(summary = "修改知识条目", description = "创建者本人或 ADMIN；PUT 为整体替换")
    public ApiResponse<KbItemResponse> updateItem(@PathVariable Long id,
                                                  @Valid @RequestBody UpdateItemRequest request) {
        return ApiResponse.ok(kbService.updateItem(id, request, currentUserProvider.require()));
    }

    @DeleteMapping("/items/{id}")
    @Operation(summary = "删除知识条目", description = "创建者本人或 ADMIN")
    public ApiResponse<Void> deleteItem(@PathVariable Long id) {
        kbService.deleteItem(id, currentUserProvider.require());
        return ApiResponse.ok();
    }

    @GetMapping("/search")
    @Operation(summary = "关键词检索", description = "标题 / 正文 / 标签均参与匹配，q 不可为空")
    public ApiResponse<PageResponse<KbItemSummary>> search(
            @Parameter(description = "关键词", required = true) @RequestParam String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(kbService.search(q, page, size));
    }
}
