package com.yibi.backend.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin pages: users, the audit log, content reports, system status. */
public final class AdminDtos {

    private AdminDtos() { }

    // ── Users ───────────────────────────────────────────────────────────────────────────────────────────────────

    public record AdminUserRow(UUID id, String username, String email, String role, boolean active, Integer hskLevel,
                               boolean googleLinked, LocalDateTime createdAt, LocalDateTime lastLogin,
                               int lessonsContributed, int lessonsPublished, int openReports) { }

    public record AdminUserPage(List<AdminUserRow> users, int page, int totalPages, long total) { }

    public record SetActiveRequest(@NotNull Boolean active, @Size(max = 300) String reason) { }

    public record SetRoleRequest(@NotBlank String role) { }

    // ── Audit log ───────────────────────────────────────────────────────────────────────────────────────────────

    public record AuditEntry(long id, String adminUsername, String action, String targetType, String targetId,
                             String detail, LocalDateTime createdAt) { }

    public record AuditPage(List<AuditEntry> entries, int page, int totalPages, long total) { }

    // ── Content reports ─────────────────────────────────────────────────────────────────────────────────────────

    /** {@code targetRef}: the sentence index for LISTENING_SEGMENT. */
    public record CreateReportRequest(@NotBlank String targetType, @NotNull Long targetId, Integer targetRef,
                                      @NotBlank String reason, @Size(max = 500) String note) { }

    /**
     * A report with what it points at: {@code targetTitle} (lesson title or word), {@code targetText} (the reported
     * sentence or the word's meaning), and {@code link} (where an admin fixes it).
     */
    public record ReportRow(long id, String targetType, long targetId, Integer targetRef, String reason, String note,
                            String status, String reporterName, LocalDateTime createdAt, String targetTitle,
                            String targetText, String link, String resolutionNote, String resolvedByName,
                            LocalDateTime resolvedAt, int sameTargetOpen) { }

    public record ReportPage(List<ReportRow> reports, int page, int totalPages, long total, int openCount) { }

    public record ResolveReportRequest(@NotBlank String status, @Size(max = 500) String note) { }

    // ── System ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** Gemini calls today by feature and model: outcome ("ok", "429", "503", "other") → count. */
    public record GeminiUsage(String feature, String model, Map<String, Long> outcomes) { }

    public record ServiceCheck(String name, boolean up, String detail) { }

    public record SystemStatus(int geminiKeys, List<String> geminiModels, List<GeminiUsage> geminiToday,
                               long sentencesTranslatedToday, long cachedTranslations, List<ServiceCheck> services,
                               long mediaFiles, long mediaBytes, long usersTotal, long usersActiveToday,
                               long openReports, long pendingLessons) { }
}
