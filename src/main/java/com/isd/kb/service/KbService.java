package com.isd.kb.service;

import java.util.List;

import com.isd.auth.domain.UserRole;
import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.result.PageResponse;
import com.isd.kb.dto.CreateCategoryRequest;
import com.isd.kb.dto.CreateItemRequest;
import com.isd.kb.dto.KbCategoryResponse;
import com.isd.kb.dto.KbItemResponse;
import com.isd.kb.dto.KbItemSummary;
import com.isd.kb.dto.UpdateItemRequest;
import com.isd.kb.entity.KbCategory;
import com.isd.kb.entity.KbItem;
import com.isd.kb.mapper.KbCategoryMapper;
import com.isd.kb.mapper.KbItemMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库业务。
 * 写操作权限：分类只能 ADMIN 建；条目 SUPPORT / ADMIN 可建，
 * 但改和删只能动自己创建的（ADMIN 例外）—— 这是本项目的第三类资源级权限。
 */
@Service
public class KbService {

    private static final int MAX_PAGE_SIZE = 100;

    private final KbCategoryMapper categoryMapper;
    private final KbItemMapper itemMapper;

    public KbService(KbCategoryMapper categoryMapper, KbItemMapper itemMapper) {
        this.categoryMapper = categoryMapper;
        this.itemMapper = itemMapper;
    }

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<KbCategoryResponse> listCategories() {
        return categoryMapper.findAll().stream().map(KbCategoryResponse::from).toList();
    }

    @Transactional
    public KbCategoryResponse createCategory(CreateCategoryRequest request, LoginUser operator) {
        requireRole(operator, UserRole.ADMIN, "只有管理员可以新建分类");
        String name = request.name().trim();
        if (categoryMapper.findByName(name).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "分类已存在: " + name);
        }
        KbCategory category = new KbCategory();
        category.setName(name);
        category.setDescription(request.description());
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException ex) {
            // 并发创建同名分类时，唯一索引是最终裁判
            throw new BusinessException(ErrorCode.CONFLICT, "分类已存在: " + name);
        }
        return KbCategoryResponse.from(reloadCategory(category.getId()));
    }

    // ------------------------------------------------------------------
    // 条目
    // ------------------------------------------------------------------

    @Transactional
    public KbItemResponse createItem(CreateItemRequest request, LoginUser operator) {
        requireWriteRole(operator);
        requireCategory(request.categoryId());

        KbItem item = new KbItem();
        item.setCategoryId(request.categoryId());
        item.setTitle(request.title().trim());
        item.setContent(request.content());
        item.setTags(normalizeTags(request.tags()));
        item.setCreatedBy(operator.getId());
        itemMapper.insert(item);

        return KbItemResponse.from(reloadItem(item.getId()));
    }

    @Transactional(readOnly = true)
    public KbItemResponse getItem(Long id) {
        return KbItemResponse.from(reloadItem(id));
    }

    /**
     * 列表：不返回正文。分页参数在 Service 里钳制，Controller 不再重复校验。
     */
    @Transactional(readOnly = true)
    public PageResponse<KbItemSummary> listItems(Long categoryId, int page, int size) {
        return query(categoryId, null, page, size);
    }

    /**
     * 关键词检索。q 为空直接 400：
     * 返回全库数据会让"搜索"变成"翻页"，调用方无法区分"没匹配"和"没填关键词"。
     */
    @Transactional(readOnly = true)
    public PageResponse<KbItemSummary> search(String keyword, int page, int size) {
        if (isBlank(keyword)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "检索关键词 q 不能为空");
        }
        return query(null, likePattern(keyword), page, size);
    }

    @Transactional
    public KbItemResponse updateItem(Long id, UpdateItemRequest request, LoginUser operator) {
        requireWriteRole(operator);
        KbItem existing = reloadItem(id);
        requireOwnerOrAdmin(existing, operator, "只有创建者或管理员可以修改该条目");
        requireCategory(request.categoryId());

        KbItem updated = new KbItem();
        updated.setId(id);
        updated.setCategoryId(request.categoryId());
        updated.setTitle(request.title().trim());
        updated.setContent(request.content());
        updated.setTags(normalizeTags(request.tags()));
        itemMapper.update(updated);

        return KbItemResponse.from(reloadItem(id));
    }

    @Transactional
    public void deleteItem(Long id, LoginUser operator) {
        requireWriteRole(operator);
        KbItem existing = reloadItem(id);
        requireOwnerOrAdmin(existing, operator, "只有创建者或管理员可以删除该条目");
        itemMapper.deleteById(id);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private PageResponse<KbItemSummary> query(Long categoryId, String keywordPattern, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        long total = itemMapper.countSearch(categoryId, keywordPattern);
        if (total == 0) {
            return PageResponse.of(safePage, safeSize, 0, List.of());
        }
        List<KbItemSummary> items = itemMapper
                .search(categoryId, keywordPattern, (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(KbItemSummary::from)
                .toList();
        return PageResponse.of(safePage, safeSize, total, items);
    }

    /**
     * LIKE 通配符转义。
     * 不转义的话，用户搜一个 "%" 就等于搜全库，"_" 会匹配任意单字符 —— 这是"通配符注入"，
     * 不是 SQL 注入（占位符挡住了），但同样能让检索结果失真。
     */
    private String likePattern(String keyword) {
        String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private void requireCategory(Long categoryId) {
        categoryMapper.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在: id=" + categoryId));
    }

    private void requireWriteRole(LoginUser operator) {
        if (operator.getRole() != UserRole.SUPPORT && operator.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有技术支持或管理员可以维护知识库");
        }
    }

    private void requireRole(LoginUser operator, UserRole expected, String message) {
        if (operator.getRole() != expected) {
            throw new BusinessException(ErrorCode.FORBIDDEN, message);
        }
    }

    private void requireOwnerOrAdmin(KbItem item, LoginUser operator, String message) {
        if (operator.getRole() != UserRole.ADMIN && !item.getCreatedBy().equals(operator.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, message);
        }
    }

    private KbCategory reloadCategory(Long id) {
        return categoryMapper.findById(id)
                .orElseThrow(() -> new IllegalStateException("分类回查失败，id=" + id));
    }

    private KbItem reloadItem(Long id) {
        return itemMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识条目不存在: id=" + id));
    }

    private String normalizeTags(String tags) {
        return isBlank(tags) ? null : tags.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
