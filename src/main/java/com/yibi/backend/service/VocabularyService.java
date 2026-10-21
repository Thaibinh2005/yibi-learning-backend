package com.yibi.backend.service;

import com.yibi.backend.dto.vocabulary.VocabularyRequest;
import com.yibi.backend.dto.vocabulary.VocabularyResponse;
import com.yibi.backend.entity.Vocabulary;
import com.yibi.backend.exception.ResourceNotFoundException;
import com.yibi.backend.repository.VocabularyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VocabularyService {

    private final VocabularyRepository vocabularyRepository;

    @Transactional(readOnly = true)
    public List<VocabularyResponse> getVocabularies(Integer hskLevel, String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Vocabulary> pageResult;

        if (search != null && !search.isBlank()) {
            pageResult = vocabularyRepository.search(search.trim(), pageable);
        } else if (hskLevel != null) {
            pageResult = vocabularyRepository.findByHskLevel(hskLevel, pageable);
        } else {
            pageResult = vocabularyRepository.findAll(pageable);
        }

        return pageResult.getContent().stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public VocabularyResponse getVocabularyById(Long id) {
        Vocabulary vocabulary = vocabularyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vocabulary", "id", id));
        return mapToResponse(vocabulary);
    }

    @Transactional
    public VocabularyResponse createVocabulary(VocabularyRequest request) {
        Vocabulary vocabulary = Vocabulary.builder()
                .hanzi(request.getHanzi())
                .hanziTraditional(request.getHanziTraditional())
                .pinyin(request.getPinyin())
                .meaningVi(request.getMeaningVi())
                .meaningEn(request.getMeaningEn())
                .hskLevel(request.getHskLevel())
                .partOfSpeech(request.getPartOfSpeech())
                .exampleSentence(request.getExampleSentence())
                .examplePinyin(request.getExamplePinyin())
                .audioUrl(request.getAudioUrl())
                .strokeCount(request.getStrokeCount())
                .build();

        Vocabulary saved = vocabularyRepository.save(vocabulary);
        log.info("Created vocabulary: id={}, hanzi={}, pinyin={}", saved.getId(), saved.getHanzi(), saved.getPinyin());
        return mapToResponse(saved);
    }

    @Transactional
    public VocabularyResponse updateVocabulary(Long id, VocabularyRequest request) {
        Vocabulary vocabulary = vocabularyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vocabulary", "id", id));

        vocabulary.setHanzi(request.getHanzi());
        vocabulary.setHanziTraditional(request.getHanziTraditional());
        vocabulary.setPinyin(request.getPinyin());
        vocabulary.setMeaningVi(request.getMeaningVi());
        vocabulary.setMeaningEn(request.getMeaningEn());
        vocabulary.setHskLevel(request.getHskLevel());
        vocabulary.setPartOfSpeech(request.getPartOfSpeech());
        vocabulary.setExampleSentence(request.getExampleSentence());
        vocabulary.setExamplePinyin(request.getExamplePinyin());
        vocabulary.setAudioUrl(request.getAudioUrl());
        vocabulary.setStrokeCount(request.getStrokeCount());

        Vocabulary updated = vocabularyRepository.save(vocabulary);
        log.info("Updated vocabulary id={}", id);
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteVocabulary(Long id) {
        if (!vocabularyRepository.existsById(id)) {
            throw new ResourceNotFoundException("Vocabulary", "id", id);
        }
        vocabularyRepository.deleteById(id);
        log.info("Deleted vocabulary id={}", id);
    }

    private VocabularyResponse mapToResponse(Vocabulary v) {
        return VocabularyResponse.builder()
                .id(v.getId())
                .hanzi(v.getHanzi())
                .hanziTraditional(v.getHanziTraditional())
                .pinyin(v.getPinyin())
                .meaningVi(v.getMeaningVi())
                .meaningEn(v.getMeaningEn())
                .hskLevel(v.getHskLevel())
                .partOfSpeech(v.getPartOfSpeech())
                .exampleSentence(v.getExampleSentence())
                .examplePinyin(v.getExamplePinyin())
                .audioUrl(v.getAudioUrl())
                .strokeCount(v.getStrokeCount())
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }
}
