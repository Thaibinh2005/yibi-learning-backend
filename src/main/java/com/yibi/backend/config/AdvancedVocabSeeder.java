package com.yibi.backend.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.entity.Vocabulary;
import com.yibi.backend.repository.VocabularyRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * =========================================================================================
 * 🚀 ADVANCED VOCABULARY SEEDER (HSK Extended Dataset)
 * =========================================================================================
 * 
 * // TODO: BẠN HÃY PASTE FILE JSON DỮ LIỆU CỦA BẠN VÀO ĐÂY:
 * // ĐƯỜNG DẪN FILE: src/main/resources/data/hsk_rich_data.json
 * 
 * Lưu ý:
 * - KHÔNG tải dữ liệu trực tiếp từ Internet trong quá trình khởi động.
 * - Hãy tải file JSON chứa bộ dữ liệu mở rộng từ Github/Tatoeba về máy của bạn.
 * - Sau đó dán hoặc đổi tên file thành 'hsk_rich_data.json' đặt vào thư mục:
 *   yibi-backend/src/main/resources/data/hsk_rich_data.json
 * 
 * Cấu trúc JSON dự kiến:
 * [
 *   {
 *     "hanzi": "学习",
 *     "hanziTraditional": "學習",
 *     "pinyin": "xuéxí",
 *     "meaningVi": "Học tập, học",
 *     "meaningEn": "to study, to learn",
 *     "hskLevel": 1,
 *     "partOfSpeech": "verb",
 *     "strokeCount": 11,
 *     "examples": [
 *       {
 *         "sentence": "他在中国学习汉语。",
 *         "pinyin": "Tā zài Zhōngguó xuéxí Hànyǔ.",
 *         "translation": "Anh ấy học tiếng Trung ở Trung Quốc."
 *       }
 *     ]
 *   }
 * ]
 * =========================================================================================
 */
@Slf4j
@Component
@Order(1) // Chạy trước DataInitializer để nạp rich data nếu có
@RequiredArgsConstructor
public class AdvancedVocabSeeder implements CommandLineRunner {

    private final VocabularyRepository vocabularyRepository;
    private final ObjectMapper objectMapper;

    private static final String DATA_RESOURCE_PATH = "data/hsk_rich_data.json";
    private static final String LOCAL_FILE_FALLBACK = "src/main/resources/data/hsk_rich_data.json";

    @Override
    @Transactional
    public void run(String... args) {
        log.info("=========================================================================================");
        log.info("🔍 [AdvancedVocabSeeder] Bắt đầu kiểm tra dữ liệu từ vựng HSK mở rộng...");
        log.info("👉 HƯỚNG DẪN: Đặt file dữ liệu mở rộng tại: src/main/resources/data/hsk_rich_data.json");
        log.info("=========================================================================================");

        long currentCount = vocabularyRepository.count();
        if (currentCount > 13) {
            log.info("ℹ️ [AdvancedVocabSeeder] Bảng vocabulary hiện đã có {} bản ghi. Bỏ qua tự động nạp để bảo vệ dữ liệu hiện có.", currentCount);
            return;
        }

        try {
            InputStream inputStream = null;
            Resource resource = new ClassPathResource(DATA_RESOURCE_PATH);

            if (resource.exists()) {
                inputStream = resource.getInputStream();
            } else {
                Path localPath = Paths.get(LOCAL_FILE_FALLBACK);
                if (Files.exists(localPath)) {
                    inputStream = Files.newInputStream(localPath);
                }
            }

            if (inputStream == null) {
                log.warn("⚠️ [AdvancedVocabSeeder] Không tìm thấy file dữ liệu tại '{}'.", DATA_RESOURCE_PATH);
                log.warn("👉 // TODO: BẠN HÃY PASTE FILE JSON DỮ LIỆU CỦA BẠN VÀO ĐÂY: src/main/resources/data/hsk_rich_data.json");
                return;
            }

            // Đọc dữ liệu JSON qua ObjectMapper
            List<VocabImportItem> items = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<VocabImportItem>>() {}
            );

            if (items == null || items.isEmpty()) {
                log.warn("⚠️ [AdvancedVocabSeeder] File '{}' rỗng. Chưa có bản ghi nào để nạp.", DATA_RESOURCE_PATH);
                return;
            }

            // Nếu chỉ có 13 từ mẫu ban đầu, dọn dẹp để nạp trọn vẹn bộ dữ liệu HSK mở rộng mới
            if (currentCount > 0 && currentCount <= 13) {
                log.info("🔄 [AdvancedVocabSeeder] Phát hiện {} từ mẫu cũ, đang làm mới với bộ dữ liệu mở rộng ({} từ)...", currentCount, items.size());
                vocabularyRepository.deleteAll();
            }

            List<Vocabulary> vocabulariesToSave = new ArrayList<>();
            for (VocabImportItem item : items) {
                if (item.getHanzi() == null || item.getHanzi().isBlank()) {
                    continue;
                }

                // Trích xuất câu ví dụ đầu tiên nếu có mảng examples
                String sentence = item.getExampleSentence();
                String pinyin = item.getExamplePinyin();

                if (item.getExamples() != null && !item.getExamples().isEmpty()) {
                    ExampleItem firstEx = item.getExamples().get(0);
                    if (firstEx.getSentence() != null && !firstEx.getSentence().isBlank()) {
                        sentence = firstEx.getSentence();
                    }
                    if (firstEx.getPinyin() != null && !firstEx.getPinyin().isBlank()) {
                        pinyin = firstEx.getPinyin();
                    }
                }

                // Xử lý nghĩa tiếng Việt và tiếng Anh từ mảng translations nếu chưa có
                String meaningEn = item.getMeaningEn();
                String meaningVi = item.getMeaningVi();

                if ((meaningEn == null || meaningEn.isBlank()) && item.getTranslations() != null && !item.getTranslations().isEmpty()) {
                    meaningEn = String.join(", ", item.getTranslations());
                }
                if (meaningVi == null || meaningVi.isBlank()) {
                    meaningVi = meaningEn != null ? meaningEn : item.getHanzi();
                }

                Vocabulary vocab = Vocabulary.builder()
                        .hanzi(item.getHanzi().trim())
                        .hanziTraditional(item.getHanziTraditional())
                        .pinyin(item.getPinyin() != null ? item.getPinyin().trim() : "")
                        .meaningVi(meaningVi)
                        .meaningEn(meaningEn)
                        .hskLevel(item.getHskLevel() != null ? item.getHskLevel() : 1)
                        .partOfSpeech(item.getPartOfSpeech())
                        .exampleSentence(sentence)
                        .examplePinyin(pinyin)
                        .audioUrl(item.getAudioUrl())
                        .strokeCount(item.getStrokeCount())
                        .build();

                vocabulariesToSave.add(vocab);
            }

            if (!vocabulariesToSave.isEmpty()) {
                vocabularyRepository.saveAll(vocabulariesToSave);
                log.info("✅ [AdvancedVocabSeeder] Đã nạp thành công {} từ vựng từ file '{}' vào cơ sở dữ liệu!",
                        vocabulariesToSave.size(), DATA_RESOURCE_PATH);
            }

        } catch (Exception e) {
            log.error("❌ [AdvancedVocabSeeder] Lỗi khi nạp dữ liệu từ vựng từ JSON: {}", e.getMessage(), e);
        }
    }

    /**
     * DTO đại diện cho mỗi phần tử từ vựng trong file JSON nhập vào.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VocabImportItem {
        private Long id;
        private String hanzi;
        private String hanziTraditional;
        private String pinyin;
        private String meaningVi;
        private String meaningEn;
        private List<String> translations;
        private Integer hskLevel;
        private String partOfSpeech;
        private Integer strokeCount;
        private String audioUrl;
        private String exampleSentence;
        private String examplePinyin;

        /** Mảng các câu ví dụ phong phú từ dataset mở rộng (Tatoeba/Github) */
        private List<ExampleItem> examples;
    }

    /**
     * DTO đại diện cho câu ví dụ chi tiết.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExampleItem {
        private String sentence;
        private String pinyin;
        private String translation;
    }
}
