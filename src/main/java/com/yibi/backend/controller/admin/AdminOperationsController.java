package com.yibi.backend.controller.admin;

import com.yibi.backend.dto.admin.AdminDtos.AdminUserPage;
import com.yibi.backend.dto.admin.AdminDtos.AuditPage;
import com.yibi.backend.dto.admin.AdminDtos.ReportPage;
import com.yibi.backend.dto.admin.AdminDtos.ResolveReportRequest;
import com.yibi.backend.dto.admin.AdminDtos.SetActiveRequest;
import com.yibi.backend.dto.admin.AdminDtos.SetRoleRequest;
import com.yibi.backend.dto.admin.AdminDtos.SystemStatus;
import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.AdminAuditService;
import com.yibi.backend.service.AdminUserService;
import com.yibi.backend.service.ContentReportService;
import com.yibi.backend.service.SystemStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Running the site: users, the audit log, content reports and system status. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminOperationsController {

    private final AdminUserService users;
    private final AdminAuditService audit;
    private final ContentReportService reports;
    private final SystemStatusService system;
    private final SecurityUtils securityUtils;

    // ── Users ───────────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<AdminUserPage>> users(@RequestParam(required = false) String q,
                                                            @RequestParam(required = false) String role,
                                                            @RequestParam(required = false) Boolean active,
                                                            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(ApiResponse.success(users.list(q, role, active, page)));
    }

    @PutMapping("/users/{id}/active")
    public ResponseEntity<ApiResponse<Void>> setActive(@PathVariable UUID id, @Valid @RequestBody SetActiveRequest request) {
        users.setActive(securityUtils.getCurrentUserId(), id, request.active(), request.reason());
        return ResponseEntity.ok(ApiResponse.success(request.active() ? "Đã mở khoá tài khoản" : "Đã khoá tài khoản", null));
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<ApiResponse<Void>> setRole(@PathVariable UUID id, @Valid @RequestBody SetRoleRequest request) {
        users.setRole(securityUtils.getCurrentUserId(), id, request.role());
        return ResponseEntity.ok(ApiResponse.success("Đã đổi quyền", null));
    }

    // ── Audit log ───────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/audit")
    public ResponseEntity<ApiResponse<AuditPage>> audit(@RequestParam(required = false) String action,
                                                        @RequestParam(required = false) String q,
                                                        @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(ApiResponse.success(audit.list(action, q, page)));
    }

    // ── Content reports ─────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<ReportPage>> reports(@RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(ApiResponse.success(reports.list(status, type, page)));
    }

    @PostMapping("/reports/{id}/resolve")
    public ResponseEntity<ApiResponse<Void>> resolve(@PathVariable long id, @Valid @RequestBody ResolveReportRequest request) {
        reports.resolve(securityUtils.getCurrentUserId(), id, request.status(), request.note());
        return ResponseEntity.ok(ApiResponse.success("Đã xử lý báo lỗi", null));
    }

    // ── System ──────────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/system")
    public ResponseEntity<ApiResponse<SystemStatus>> system() {
        return ResponseEntity.ok(ApiResponse.success(system.status()));
    }
}
