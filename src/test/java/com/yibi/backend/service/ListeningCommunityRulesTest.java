package com.yibi.backend.service;

import com.yibi.backend.dto.listening.ListeningDtos.Source;
import com.yibi.backend.dto.listening.ListeningDtos.Visibility;
import com.yibi.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListeningCommunityRulesTest {

    private static final UUID CONTRIBUTOR = UUID.randomUUID();
    private static final ListeningViewer OWNER = new ListeningViewer(CONTRIBUTOR, false);
    private static final ListeningViewer OTHER = new ListeningViewer(UUID.randomUUID(), false);
    private static final ListeningViewer ADMIN = new ListeningViewer(UUID.randomUUID(), true);

    private static LearnerVideoService.Lesson lesson(Visibility visibility, String status) {
        return new LearnerVideoService.Lesson(1, "t", Source.YOUTUBE, CONTRIBUTOR, visibility, status, null, "DONE", null,
                1L, "YOUTUBE", "abcDEF12345", null, null, "Kênh", null, "an", null, "YOUTUBE_EMBED", null, null, 1, 1,
                List.of(), null, false);
    }

    @Test
    void othersSeeOnlyFinishedPublishedLessons() {
        assertThat(LearnerVideoService.readable(OTHER, lesson(Visibility.PRIVATE, "READY"))).isFalse();
        assertThat(LearnerVideoService.readable(OTHER, lesson(Visibility.PENDING_REVIEW, "READY"))).isFalse();
        assertThat(LearnerVideoService.readable(OTHER, lesson(Visibility.HIDDEN, "READY"))).isFalse();
        assertThat(LearnerVideoService.readable(OTHER, lesson(Visibility.PUBLISHED, "DRAFT"))).isFalse();
        assertThat(LearnerVideoService.readable(OTHER, lesson(Visibility.PUBLISHED, "READY"))).isTrue();
        assertThat(LearnerVideoService.readable(ListeningViewer.GUEST, lesson(Visibility.PRIVATE, "READY"))).isFalse();
        assertThat(LearnerVideoService.readable(OWNER, lesson(Visibility.HIDDEN, "DRAFT"))).isTrue();
        assertThat(LearnerVideoService.readable(ADMIN, lesson(Visibility.PRIVATE, "DRAFT"))).isTrue();
    }

    @Test
    void contributorsEditOnlyWhileNotInTheCommunity() {
        assertThat(LearnerVideoService.editable(OWNER, lesson(Visibility.PRIVATE, "READY"))).isTrue();
        assertThat(LearnerVideoService.editable(OWNER, lesson(Visibility.REJECTED, "READY"))).isTrue();
        assertThat(LearnerVideoService.editable(OWNER, lesson(Visibility.PENDING_REVIEW, "READY"))).isFalse();
        assertThat(LearnerVideoService.editable(OWNER, lesson(Visibility.PUBLISHED, "READY"))).isFalse();
        assertThat(LearnerVideoService.editable(OTHER, lesson(Visibility.PUBLISHED, "READY"))).isFalse();
        assertThat(LearnerVideoService.editable(ADMIN, lesson(Visibility.PUBLISHED, "READY"))).isTrue();
    }

    @Test
    void makesSlugsFromVietnameseNames() {
        assertThat(ListeningAdminService.slugify("Hội thoại hằng ngày")).isEqualTo("hoi-thoai-hang-ngay");
        assertThat(ListeningAdminService.slugify("  Đời sống & Văn hoá!! ")).isEqualTo("doi-song-van-hoa");
        assertThat(ListeningAdminService.slugify("中文")).isEmpty();
    }

    @Test
    void filtersOnlyKnownLevelsAndEscapesSearch() {
        assertThat(ListeningLibraryService.Filter.of("7-9", null).args()).containsExactly("7-9");
        assertThat(ListeningLibraryService.Filter.of(null, "50%_off").args()).containsExactly("%50\\%\\_off%", "%50\\%\\_off%");
        assertThat(ListeningLibraryService.Filter.of("", " ").sql()).isEmpty();
        assertThatThrownBy(() -> ListeningLibraryService.Filter.of("8", null)).isInstanceOf(AppException.class);
    }
}
