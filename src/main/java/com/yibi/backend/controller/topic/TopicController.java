package com.yibi.backend.controller.topic;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.topic.TopicResponse;
import com.yibi.backend.dto.vocabulary.VocabularyResponse;
import com.yibi.backend.service.TopicService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/topics")
@RequiredArgsConstructor
public class TopicController {

    private final TopicService topicService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TopicResponse>>> getTopics(
            @RequestParam(required = false) String hskLevel,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "count") String sort) {
        List<TopicResponse> list = topicService.getTopics(hskLevel, search, sort);
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ApiResponse<TopicResponse>> getTopicBySlug(
            @PathVariable String slug,
            @RequestParam(required = false) String hskLevel) {
        TopicResponse topic = topicService.getTopicBySlug(slug, hskLevel);
        return ResponseEntity.ok(ApiResponse.success(topic));
    }

    @GetMapping("/{slug}/vocabularies")
    public ResponseEntity<ApiResponse<List<VocabularyResponse>>> getVocabulariesByTopic(
            @PathVariable String slug,
            @RequestParam(required = false) String hskLevel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<VocabularyResponse> list = topicService.getVocabulariesByTopic(slug, hskLevel, page, size);
        return ResponseEntity.ok(ApiResponse.success(list));
    }
}

