package com.yibi.backend.dto.video;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Subtitles read from YouTube for review, and the reviewed copies saved as the source of listening exercises. */
public final class VideoTranscriptDtos {

    private VideoTranscriptDtos() { }

    public enum UsageRights { OWN, CREATIVE_COMMONS, PERMISSION }

    /** @param script simplified, traditional, unknown (plain "zh"), or null for a non-Chinese track */
    public record YoutubeLanguage(String languageCode, String language,
                                  @JsonProperty("isGenerated") boolean isGenerated, String script) { }

    public record YoutubeLanguages(String videoId, List<YoutubeLanguage> languages, String recommended,
                                   Boolean recommendedIsGenerated) { }

    /** Times in seconds; {@code end = start + duration}. */
    public record TranscriptSegment(String text, double start, double duration, double end) { }

    /** @param traditionalChars characters that differ from their simplified form, so the admin sees a wrong script */
    public record YoutubeTranscript(String videoId, String language, String languageCode,
                                    @JsonProperty("isGenerated") boolean isGenerated, String script,
                                    List<TranscriptSegment> segments, int traditionalChars) { }

    public record SegmentInput(@NotNull @Size(max = 300) String text, @NotNull Double start, @NotNull Double end) { }

    public record SaveTranscriptRequest(
            @NotBlank String videoId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20) String languageCode,
            @JsonProperty("isGenerated") boolean isGenerated,
            @Size(max = 5) String hskLevel,
            @NotNull UsageRights usageRights,
            @Size(max = 500) String rightsNote,
            boolean rightsConfirmed,
            @NotEmpty @Size(max = 3000) List<@Valid SegmentInput> segments) { }
}
