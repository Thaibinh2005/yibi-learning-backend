package com.yibi.backend.controller.vocabulary;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.vocabulary.VocabularyRequest;
import com.yibi.backend.dto.vocabulary.VocabularyResponse;
import com.yibi.backend.service.VocabularyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vocabularies")
@RequiredArgsConstructor
public class VocabularyController {

    private final VocabularyService vocabularyService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<VocabularyResponse>>> getVocabularies(
            @RequestParam(required = false) Integer hskLevel,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<VocabularyResponse> list = vocabularyService.getVocabularies(hskLevel, search, page, size);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<VocabularyResponse>> getVocabularyById(@PathVariable Long id) {
        VocabularyResponse response = vocabularyService.getVocabularyById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<VocabularyResponse>> createVocabulary(@Valid @RequestBody VocabularyRequest request) {
        VocabularyResponse created = vocabularyService.createVocabulary(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Vocabulary created successfully", created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<VocabularyResponse>> updateVocabulary(
            @PathVariable Long id,
            @Valid @RequestBody VocabularyRequest request) {
        VocabularyResponse updated = vocabularyService.updateVocabulary(id, request);
        return ResponseEntity.ok(ApiResponse.success("Vocabulary updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteVocabulary(@PathVariable Long id) {
        vocabularyService.deleteVocabulary(id);
        return ResponseEntity.ok(ApiResponse.success("Vocabulary deleted successfully", null));
    }
}
