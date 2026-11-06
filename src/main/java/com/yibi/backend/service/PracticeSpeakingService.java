package com.yibi.backend.service;

import com.yibi.backend.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Scores a read-aloud answer in a practice session. The slow speech call runs outside any transaction. */
@Service
@RequiredArgsConstructor
public class PracticeSpeakingService {

    private final QuestionBankService questionBankService;
    private final SpeechScoringClient speechClient;

    @Value("${app.speech.pass-accuracy:0.7}")
    private double passAccuracy;

    public boolean isAvailable() {
        return speechClient.isAvailable();
    }

    public Map<String, Object> answer(Long sessionId, UUID userId, String questionCode, MultipartFile audio, Integer responseTimeMs) {
        String target = questionBankService.readAloudTarget(sessionId, userId, questionCode);
        if (audio == null || audio.isEmpty()) throw new BadRequestException("Chưa có bản ghi âm.");
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("Không đọc được bản ghi âm.");
        }
        String filename = audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "speech.webm";
        double accuracy = speechClient.score(bytes, filename, target);
        return questionBankService.recordSpeakingAnswer(sessionId, userId, questionCode, accuracy,
                accuracy >= passAccuracy, responseTimeMs);
    }
}
