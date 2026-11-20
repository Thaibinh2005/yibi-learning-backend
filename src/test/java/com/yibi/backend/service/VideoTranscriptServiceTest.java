package com.yibi.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.video.VideoTranscriptDtos.SegmentInput;
import com.yibi.backend.dto.video.VideoTranscriptDtos.TranscriptSegment;
import com.yibi.backend.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoTranscriptServiceTest {

    @Test
    void normalizeTrimsDropsEmptySortsAndRecomputesDuration() {
        List<TranscriptSegment> out = VideoTranscriptService.normalize(List.of(
                new SegmentInput("  谢谢  你 ", 4.0, 5.25),
                new SegmentInput("   ", 2.0, 3.0),
                new SegmentInput("你好", 1.00049, 3.5)));
        assertThat(out).containsExactly(
                new TranscriptSegment("你好", 1.0, 2.5, 3.5),
                new TranscriptSegment("谢谢 你", 4.0, 1.25, 5.25));
    }

    @Test
    void normalizeRejectsBadTimesWithTheSegmentNumber() {
        assertThatThrownBy(() -> VideoTranscriptService.normalize(List.of(
                new SegmentInput("好", 0.0, 1.0), new SegmentInput("坏", 3.0, 3.0))))
                .isInstanceOf(AppException.class).hasMessageContaining("Đoạn 2");
        assertThatThrownBy(() -> VideoTranscriptService.normalize(List.of(new SegmentInput("坏", -1.0, 1.0))))
                .isInstanceOf(AppException.class);
    }

    @Test
    void normalizeRejectsATranscriptWithNothingLeft() {
        assertThatThrownBy(() -> VideoTranscriptService.normalize(List.of(new SegmentInput(" ", 0.0, 1.0))))
                .isInstanceOf(AppException.class).hasMessageContaining("không còn đoạn");
    }

    @Test
    void countsTraditionalCharacters() {
        assertThat(YoutubeTranscriptClient.traditionalChars("你好，谢谢")).isZero();
        assertThat(YoutubeTranscriptClient.traditionalChars("謝謝你，再見 ok")).isEqualTo(3);
    }

    @Test
    void keepsTheServiceMessageAndMapsTheStatus() {
        YoutubeTranscriptClient client = new YoutubeTranscriptClient("http://localhost:1", new ObjectMapper());
        AppException blocked = client.fromService(429,
                "{\"detail\":{\"code\":\"REQUEST_BLOCKED\",\"message\":\"YouTube đang chặn\",\"available\":[]}}");
        assertThat(blocked.getMessage()).isEqualTo("YouTube đang chặn");
        assertThat(blocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        AppException restricted = client.fromService(403, "{\"detail\":{\"message\":\"giới hạn độ tuổi\"}}");
        assertThat(restricted.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        AppException garbage = client.fromService(500, "<html>");
        assertThat(garbage.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(garbage.getMessage()).contains("Không đọc được phụ đề");
    }
}
