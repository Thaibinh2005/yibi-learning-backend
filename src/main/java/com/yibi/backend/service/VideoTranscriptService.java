package com.yibi.backend.service;

import com.yibi.backend.dto.video.VideoTranscriptDtos.SaveTranscriptRequest;
import com.yibi.backend.dto.video.VideoTranscriptDtos.SegmentInput;
import com.yibi.backend.dto.video.VideoTranscriptDtos.TranscriptSegment;
import com.yibi.backend.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The admin subtitle tool: a YouTube preview is never written until the admin saves it, and saving now creates a
 * draft listening lesson of the admin (video credited to its channel). The admin finishes it in the editor and
 * publishes it from the library tab, like any community lesson.
 */
@Service
@RequiredArgsConstructor
public class VideoTranscriptService {

    static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    static final Set<String> LEVELS = Set.of("1", "2", "3", "4", "5", "6", "7-9");

    private final LearnerVideoService lessons;

    public record CreatedLesson(long lessonId) { }

    public CreatedLesson save(UUID adminId, SaveTranscriptRequest request) {
        if (!request.rightsConfirmed()) {
            throw new AppException("Hãy xác nhận bạn có quyền dùng video này trước khi lưu.", HttpStatus.BAD_REQUEST);
        }
        if (!VIDEO_ID.matcher(request.videoId()).matches()) {
            throw new AppException("Mã video YouTube không hợp lệ.", HttpStatus.BAD_REQUEST);
        }
        if (!request.languageCode().startsWith("zh")) {
            throw new AppException("Chỉ lưu phụ đề tiếng Trung.", HttpStatus.BAD_REQUEST);
        }
        String level = blankToNull(request.hskLevel());
        if (level != null && !LEVELS.contains(level)) {
            throw new AppException("Cấp HSK không hợp lệ.", HttpStatus.BAD_REQUEST);
        }
        List<TranscriptSegment> segments = normalize(request.segments());
        String note = request.usageRights().name() + (blankToNull(request.rightsNote()) == null ? "" : ": " + request.rightsNote().trim());
        return new CreatedLesson(lessons.createFromReviewedTranscript(adminId, request.title().trim(), request.videoId(),
                request.languageCode(), request.isGenerated(), level, note.length() > 500 ? note.substring(0, 500) : note, segments));
    }

    /**
     * Trims the edited segments, drops the ones left empty, checks the times and sorts by start.
     * Times are rounded to milliseconds and the duration is recomputed from start and end.
     */
    static List<TranscriptSegment> normalize(List<SegmentInput> input) {
        List<TranscriptSegment> out = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            SegmentInput s = input.get(i);
            String text = s.text() == null ? "" : String.join(" ", s.text().trim().split("\\s+"));
            if (text.isEmpty()) continue;
            double start = round(s.start()), end = round(s.end());
            if (start < 0 || end <= start) {
                throw new AppException("Đoạn " + (i + 1) + " có thời gian không hợp lệ (kết thúc phải sau bắt đầu).",
                        HttpStatus.BAD_REQUEST);
            }
            out.add(new TranscriptSegment(text, start, round(end - start), end));
        }
        if (out.isEmpty()) throw new AppException("Phụ đề không còn đoạn nào có nội dung.", HttpStatus.BAD_REQUEST);
        out.sort(Comparator.comparingDouble(TranscriptSegment::start));
        return out;
    }

    private static double round(double seconds) {
        return Math.round(seconds * 1000) / 1000.0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
