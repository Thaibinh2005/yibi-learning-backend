package com.yibi.backend.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.AdminAuditService;
import com.yibi.backend.service.mocktest.MockTestAdminService;
import com.yibi.backend.service.mocktest.MockTestAdminService.Clip;
import com.yibi.backend.service.mocktest.MockTestAdminService.DraftView;
import com.yibi.backend.service.mocktest.MockTestAdminService.ImportView;
import com.yibi.backend.service.mocktest.MockTestAdminService.Overview;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Sample papers for admins.
 *
 * GET    /api/admin/mock-tests                    → papers (status, draft, things to check) and recent uploads
 * POST   /api/admin/mock-tests/inspect            → quick checks of a PDF (multipart "pdf")
 * POST   /api/admin/mock-tests/imports            → upload PDF + recording ("pdf", "audio", optional "level")
 * GET    /api/admin/mock-tests/imports/{id}       → progress of an upload; a finished one becomes a draft
 * DELETE /api/admin/mock-tests/imports/{id}       → remove a finished or failed upload from the list
 * GET    /api/admin/mock-tests/{testId}           → the draft (or published paper) with what blocks publishing
 * PUT    /api/admin/mock-tests/{testId}/draft     → save the edited paper
 * DELETE /api/admin/mock-tests/{testId}/draft     → drop the draft (a never published paper is deleted)
 * POST   /api/admin/mock-tests/{testId}/recut     → cut clips again with new marks ({"clips": [{key, start, end}]})
 * POST   /api/admin/mock-tests/{testId}/publish   → publish the draft as a new version
 * POST   /api/admin/mock-tests/{testId}/visibility → hide from or show to learners ({"hidden": true})
 */
@RestController
@RequestMapping("/api/admin/mock-tests")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MockTestAdminController {

    private final MockTestAdminService service;
    private final SecurityUtils securityUtils;
    private final AdminAuditService audit;

    public record RecutRequest(List<Clip> clips) {}

    public record VisibilityRequest(boolean hidden) {}

    @GetMapping
    public ResponseEntity<ApiResponse<Overview>> overview() {
        return ResponseEntity.ok(ApiResponse.success(service.overview()));
    }

    @PostMapping("/inspect")
    public ResponseEntity<ApiResponse<JsonNode>> inspect(@RequestParam("pdf") MultipartFile pdf) {
        return ResponseEntity.ok(ApiResponse.success(service.inspect(pdf)));
    }

    @PostMapping("/imports")
    public ResponseEntity<ApiResponse<ImportView>> startImport(@RequestParam("pdf") MultipartFile pdf,
                                                               @RequestParam("audio") MultipartFile audio,
                                                               @RequestParam(value = "level", required = false) Integer level) {
        ImportView started = service.startImport(securityUtils.getCurrentUserId(), pdf, audio, level);
        audit.record(securityUtils.getCurrentUserId(), "MOCK_TEST_UPLOAD", "MOCK_TEST_IMPORT", started.id(),
                started.pdfName() + " + " + started.audioName());
        return ResponseEntity.ok(ApiResponse.success("Đã tải lên, đang xử lý", started));
    }

    @GetMapping("/imports/{id}")
    public ResponseEntity<ApiResponse<ImportView>> importStatus(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.success(service.refresh(id)));
    }

    @DeleteMapping("/imports/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteImport(@PathVariable long id) {
        service.deleteImport(id);
        return ResponseEntity.ok(ApiResponse.success("Đã xoá khỏi danh sách", null));
    }

    @GetMapping("/{testId}")
    public ResponseEntity<ApiResponse<DraftView>> draft(@PathVariable String testId) {
        return ResponseEntity.ok(ApiResponse.success(service.draft(testId)));
    }

    @PutMapping("/{testId}/draft")
    public ResponseEntity<ApiResponse<DraftView>> saveDraft(@PathVariable String testId, @RequestBody JsonNode paper) {
        DraftView saved = service.saveDraft(testId, paper);
        audit.record(securityUtils.getCurrentUserId(), "MOCK_TEST_EDIT", "MOCK_TEST", testId, null);
        return ResponseEntity.ok(ApiResponse.success("Đã lưu bản nháp", saved));
    }

    @DeleteMapping("/{testId}/draft")
    public ResponseEntity<ApiResponse<Boolean>> discardDraft(@PathVariable String testId) {
        boolean deleted = service.discardDraft(testId);
        audit.record(securityUtils.getCurrentUserId(), deleted ? "MOCK_TEST_DELETE" : "MOCK_TEST_DISCARD_DRAFT", "MOCK_TEST", testId, null);
        return ResponseEntity.ok(ApiResponse.success(deleted ? "Đã xoá đề" : "Đã bỏ bản nháp", deleted));
    }

    @PostMapping("/{testId}/recut")
    public ResponseEntity<ApiResponse<DraftView>> recut(@PathVariable String testId, @RequestBody RecutRequest request) {
        DraftView updated = service.recut(testId, request.clips());
        audit.record(securityUtils.getCurrentUserId(), "MOCK_TEST_RECUT", "MOCK_TEST", testId,
                request.clips().stream().map(Clip::key).toList().toString());
        return ResponseEntity.ok(ApiResponse.success("Đã cắt lại " + request.clips().size() + " đoạn", updated));
    }

    @PostMapping("/{testId}/publish")
    public ResponseEntity<ApiResponse<Integer>> publish(@PathVariable String testId) {
        int version = service.publish(securityUtils.getCurrentUserId(), testId);
        audit.record(securityUtils.getCurrentUserId(), "MOCK_TEST_PUBLISH", "MOCK_TEST", testId, "phiên bản " + version);
        return ResponseEntity.ok(ApiResponse.success("Đã xuất bản phiên bản " + version, version));
    }

    @PostMapping("/{testId}/visibility")
    public ResponseEntity<ApiResponse<Void>> visibility(@PathVariable String testId, @RequestBody VisibilityRequest request) {
        service.setHidden(testId, request.hidden());
        audit.record(securityUtils.getCurrentUserId(), request.hidden() ? "MOCK_TEST_HIDE" : "MOCK_TEST_SHOW", "MOCK_TEST", testId, null);
        return ResponseEntity.ok(ApiResponse.success(request.hidden() ? "Đã ẩn đề" : "Đã hiện đề", null));
    }
}
