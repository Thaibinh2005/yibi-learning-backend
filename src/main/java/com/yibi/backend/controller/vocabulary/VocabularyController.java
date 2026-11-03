package com.yibi.backend.controller.vocabulary;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.vocabulary.DailyVocabularyResponse;
import com.yibi.backend.dto.vocabulary.VocabularyResponse;
import com.yibi.backend.service.VocabularyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vocabularies")
@RequiredArgsConstructor
public class VocabularyController {

    private final VocabularyService vocabularyService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<VocabularyResponse>>> getVocabularies(
            @RequestParam(required = false) String hskLevel,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<VocabularyResponse> list = vocabularyService.getVocabularies(hskLevel, search, page, size);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<List<DailyVocabularyResponse>>> getDailyVocabularies(
            @RequestParam(required = false) String hskLevel,
            @RequestParam(defaultValue = "15") int days) {
        List<DailyVocabularyResponse> list = vocabularyService.getDailyVocabularies(hskLevel, days);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @GetMapping("/random")
    public ResponseEntity<ApiResponse<DailyVocabularyResponse>> getRandomVocabulary(
            @RequestParam(required = false) String hskLevel) {
        DailyVocabularyResponse response = vocabularyService.getRandomVocabulary(hskLevel);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<VocabularyResponse>> getVocabularyById(@PathVariable Long id) {
        VocabularyResponse response = vocabularyService.getVocabularyById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

}
