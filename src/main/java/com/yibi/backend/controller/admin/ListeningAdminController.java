package com.yibi.backend.controller.admin;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.listening.ListeningDtos.AdminLessonPage;
import com.yibi.backend.dto.listening.ListeningDtos.ApproveRequest;
import com.yibi.backend.dto.listening.ListeningDtos.Category;
import com.yibi.backend.dto.listening.ListeningDtos.CategoryRequest;
import com.yibi.backend.dto.listening.ListeningDtos.RejectRequest;
import com.yibi.backend.dto.listening.ListeningDtos.ReorderRequest;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.AdminAuditService;
import com.yibi.backend.service.LearnerVideoService;
import com.yibi.backend.service.ListeningAdminService;
import com.yibi.backend.service.ListeningViewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The community library for admins: categories, and the review queue of submitted lessons. */
@RestController
@RequestMapping("/api/admin/listening")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ListeningAdminController {

    private final ListeningAdminService admin;
    private final LearnerVideoService lessons;
    private final SecurityUtils securityUtils;
    private final AdminAuditService audit;

    public record CategoryIds(@NotNull @Size(min = 1, max = 3, message = "Hãy chọn từ 1 đến 3 danh mục.") List<Long> categoryIds) { }

    public record Note(@Size(max = 500) String note) { }

    // ── Categories ──────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<Category>>> categories() {
        return ResponseEntity.ok(ApiResponse.success(admin.categories()));
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<Category>> createCategory(@Valid @RequestBody CategoryRequest request) {
        Category created = admin.createCategory(request);
        audit.record(securityUtils.getCurrentUserId(), "CATEGORY_CREATE", "CATEGORY", created.slug(), created.name());
        return ResponseEntity.ok(ApiResponse.success("Đã thêm danh mục", created));
    }

    @PutMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<Category>> updateCategory(@PathVariable long id, @Valid @RequestBody CategoryRequest request) {
        Category updated = admin.updateCategory(id, request);
        audit.record(securityUtils.getCurrentUserId(), "CATEGORY_UPDATE", "CATEGORY", updated.slug(),
                updated.name() + (updated.active() ? "" : " (tắt)"));
        return ResponseEntity.ok(ApiResponse.success("Đã lưu danh mục", updated));
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(@PathVariable long id) {
        admin.deleteCategory(id);
        audit.record(securityUtils.getCurrentUserId(), "CATEGORY_DELETE", "CATEGORY", id, null);
        return ResponseEntity.ok(ApiResponse.success("Đã xoá danh mục", null));
    }

    @PutMapping("/categories/order")
    public ResponseEntity<ApiResponse<List<Category>>> reorder(@Valid @RequestBody ReorderRequest request) {
        return ResponseEntity.ok(ApiResponse.success(admin.reorder(request.ids())));
    }

    // ── Lessons ─────────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/lessons")
    public ResponseEntity<ApiResponse<AdminLessonPage>> lessons(@RequestParam(required = false) String visibility,
                                                                @RequestParam(required = false) Long categoryId,
                                                                @RequestParam(required = false) String q,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "false") boolean editorial) {
        return ResponseEntity.ok(ApiResponse.success(admin.lessons(visibility, categoryId, q, page, editorial)));
    }

    @PostMapping("/lessons/{id}/approve")
    public ResponseEntity<ApiResponse<Void>> approve(@PathVariable long id, @Valid @RequestBody ApproveRequest request) {
        admin.approve(securityUtils.getCurrentUserId(), id, request);
        audit.record(securityUtils.getCurrentUserId(), "LESSON_PUBLISH", "LISTENING_LESSON", id,
                "danh mục " + request.categoryIds() + (request.note() == null ? "" : " · " + request.note()));
        return ResponseEntity.ok(ApiResponse.success("Đã công khai bài luyện", null));
    }

    @PostMapping("/lessons/{id}/reject")
    public ResponseEntity<ApiResponse<Void>> reject(@PathVariable long id, @Valid @RequestBody RejectRequest request) {
        admin.reject(securityUtils.getCurrentUserId(), id, request.note());
        audit.record(securityUtils.getCurrentUserId(), "LESSON_REJECT", "LISTENING_LESSON", id, request.note());
        return ResponseEntity.ok(ApiResponse.success("Đã từ chối bài luyện", null));
    }

    @PostMapping("/lessons/{id}/hide")
    public ResponseEntity<ApiResponse<Void>> hide(@PathVariable long id, @Valid @RequestBody(required = false) Note note) {
        admin.hide(securityUtils.getCurrentUserId(), id, note == null ? null : note.note());
        audit.record(securityUtils.getCurrentUserId(), "LESSON_HIDE", "LISTENING_LESSON", id, note == null ? null : note.note());
        return ResponseEntity.ok(ApiResponse.success("Đã ẩn bài luyện", null));
    }

    @PutMapping("/lessons/{id}/categories")
    public ResponseEntity<ApiResponse<Void>> setCategories(@PathVariable long id, @Valid @RequestBody CategoryIds request) {
        admin.setCategories(id, request.categoryIds());
        audit.record(securityUtils.getCurrentUserId(), "LESSON_CATEGORIES", "LISTENING_LESSON", id, String.valueOf(request.categoryIds()));
        return ResponseEntity.ok(ApiResponse.success("Đã lưu danh mục của bài", null));
    }

    @DeleteMapping("/lessons/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id) {
        lessons.delete(ListeningViewer.of(securityUtils.getCurrentUser()), id);
        audit.record(securityUtils.getCurrentUserId(), "LESSON_DELETE", "LISTENING_LESSON", id, null);
        return ResponseEntity.ok(ApiResponse.success("Đã xoá bài luyện", null));
    }
}
