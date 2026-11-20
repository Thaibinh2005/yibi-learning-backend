package com.yibi.backend.service;

import com.yibi.backend.dto.listening.ListeningDtos.LearnerSegment;
import com.yibi.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearnerVideoServiceTest {

    private static final String ID = "abcDEF12345";

    @Test
    void readsYoutubeIds() {
        for (String v : List.of(ID, "https://www.youtube.com/watch?v=" + ID + "&t=3", "youtu.be/" + ID,
                "https://m.youtube.com/shorts/" + ID, "https://www.youtube-nocookie.com/embed/" + ID)) {
            assertThat(LearnerVideoService.youtubeId(v)).as(v).isEqualTo(ID);
        }
        for (String v : List.of("", "abc", "https://evil.com/watch?v=" + ID, "https://vimeo.com/1")) {
            assertThatThrownBy(() -> LearnerVideoService.youtubeId(v)).isInstanceOf(AppException.class);
        }
    }

    @Test
    void splitsTypedTextIntoSentences() {
        List<LearnerSegment> s = LearnerVideoService.linesToSegments("你好。\r\n\n  我叫  王明。 \n");
        assertThat(s).extracting(LearnerSegment::text).containsExactly("你好。", "我叫 王明。");
        assertThat(s.get(0).start()).isNull();
        assertThatThrownBy(() -> LearnerVideoService.linesToSegments(" \n ")).isInstanceOf(AppException.class);
    }

    @Test
    void recognisesMediaByItsFirstBytes() {
        assertThat(media("ID3\u0004")).isTrue();
        assertThat(media("\u0000\u0000\u0000\u0018ftypmp42")).isTrue();
        assertThat(media("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")).isTrue();
        assertThat(media("OggS\u0000")).isTrue();
        assertThat(LearnerVideoService.looksLikeMedia(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}, 4)).isTrue();
        assertThat(media("%PDF-1.7")).isFalse();
        assertThat(media("MZ\u0090\u0000")).isFalse();
        assertThat(media("<htm")).isFalse();
    }

    private static boolean media(String head) {
        byte[] bytes = head.getBytes(StandardCharsets.ISO_8859_1);
        return LearnerVideoService.looksLikeMedia(bytes, bytes.length);
    }

    @Test
    void knowsWhichLinesNeedTranslation() {
        assertThat(LearnerVideoService.needsTranslation(seg(null, null))).isTrue();
        assertThat(LearnerVideoService.needsTranslation(seg(" ", null))).isTrue();
        assertThat(LearnerVideoService.needsTranslation(seg("Xin chào", true))).isTrue();
        assertThat(LearnerVideoService.needsTranslation(seg("Xin chào", false))).isFalse();
    }

    private static LearnerSegment seg(String vi, Boolean stale) {
        return new LearnerSegment("你好", 0.0, 1.0, null, vi, stale, null, null, null);
    }

    @Test
    void mediaTypes() {
        assertThat(LearnerVideoService.extension("Bai 1.MP3")).isEqualTo("mp3");
        assertThat(LearnerVideoService.mediaType("mp3")).isEqualTo("audio/mpeg");
        assertThat(LearnerVideoService.mediaType("mov")).isEqualTo("video/quicktime");
        assertThat(LearnerVideoService.MEDIA_EXTENSIONS).doesNotContain("exe", "html", "svg");
    }

    @Test
    void translationPromptAndChecks() {
        String prompt = SegmentTranslationService.prompt("Bài 1", List.of("我想去。"), List.of("你好。", "我想去。", "再见。"));
        assertThat(prompt).contains("1. 我想去。").contains("Câu ngay trước (chỉ để hiểu ngữ cảnh, không dịch): 你好。")
                .contains("Câu ngay sau (chỉ để hiểu ngữ cảnh, không dịch): 再见。");
        assertThat(SegmentTranslationService.usable("Tôi muốn đi.")).isTrue();
        assertThat(SegmentTranslationService.usable("Tôi 想 đi.")).isFalse();
        assertThat(SegmentTranslationService.usable("  ")).isFalse();
        assertThat(SegmentTranslationService.hash(" 你好 ")).isEqualTo(SegmentTranslationService.hash("你好")).hasSize(64);
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 65; i++) many.add("句" + (i % 61));
        assertThat(SegmentTranslationService.batches(many)).extracting(List::size).containsExactly(30, 30, 1);
    }

    @Test
    void namesVideosWithoutATitle() {
        assertThat(LearnerVideoService.titleFromFileName("C:\\Users\\me\\HSK3_bai_1.mp3")).isEqualTo("HSK3 bai 1");
        assertThat(LearnerVideoService.titleFromFileName("dir/lesson.final.m4a")).isEqualTo("lesson.final");
        assertThat(LearnerVideoService.titleFromFileName(".mp3")).isEqualTo("File âm thanh");
        assertThat(LearnerVideoService.titleFromText(LearnerVideoService.linesToSegments("你好！\n我叫王明。")))
                .isEqualTo("Bài nghe: 你好！");
        assertThat(LearnerVideoService.titleFromText(List.of())).isEqualTo("Bài nghe tự nhập");
        assertThat(YoutubeTitleClient.clean("  学中文 \n  第一课 ")).isEqualTo("学中文 第一课");
        assertThat(YoutubeTitleClient.clean("长".repeat(300))).hasSize(200).endsWith("…");
    }

    @Test
    void summarisesDurationAndLevel() {
        List<ChinesePinyinService.Token> easy = new java.util.ArrayList<>();
        for (String w : List.of("我", "你", "好", "是", "的", "不", "有", "人", "大")) easy.add(new ChinesePinyinService.Token(w, "", 1L, "1"));
        easy.add(new ChinesePinyinService.Token("经济", "", 2L, "4"));
        easy.add(new ChinesePinyinService.Token("我", "", 1L, "1"));
        LearnerSegment a = new LearnerSegment("x", 0.0, 2.5, null, null, null, easy, null, null);
        LearnerSegment b = new LearnerSegment("y", 2.5, 61.2, null, null, null, null, null, null);
        assertThat(LearnerVideoService.duration(List.of(a, b))).isEqualTo(61.2);
        assertThat(LearnerVideoService.duration(List.of(new LearnerSegment("z", null, null, null, null, null, null, null, null)))).isNull();
        assertThat(LearnerVideoService.estimateLevel(List.of(a, b))).isEqualTo("1");
        List<ChinesePinyinService.Token> hard = List.of(new ChinesePinyinService.Token("a", "", 1L, "2"), new ChinesePinyinService.Token("b", "", 1L, "3"),
                new ChinesePinyinService.Token("c", "", 1L, "5"), new ChinesePinyinService.Token("d", "", 1L, "7-9"), new ChinesePinyinService.Token("e", "", 1L, "7-9"));
        assertThat(LearnerVideoService.estimateLevel(List.of(new LearnerSegment("h", null, null, null, null, null, hard, null, null)))).isEqualTo("7-9");
        assertThat(LearnerVideoService.estimateLevel(List.of(b))).isNull();
    }
}
