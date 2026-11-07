package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yibi.backend.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.yibi.backend.service.plan.SpeechAlignment;
import org.springframework.web.client.RestTemplate;

/** Calls yibi-speech-service, which transcribes a recording and returns how close it is to the target sentence. */
@Slf4j
@Component
public class SpeechScoringClient {

    private final String baseUrl;
    private final RestTemplate scoring;
    private final RestTemplate probe;

    public SpeechScoringClient(@Value("${app.speech.base-url:http://localhost:8001}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.scoring = new RestTemplate(factory(3_000, 90_000));
        this.probe = new RestTemplate(factory(1_000, 2_000));
    }

    /** True when the service answers its health check quickly. */
    public boolean isAvailable() {
        try {
            JsonNode body = probe.getForObject(baseUrl + "/health", JsonNode.class);
            return body != null && "ok".equals(body.path("status").asText());
        } catch (RestClientException ex) {
            return false;
        }
    }

    /** @param accuracy from 0 to 1 against the target text; @param transcript what the recognizer heard */
    public record Heard(double accuracy, String transcript) { }

    /** Accuracy from 0 to 1 of the recording against the sentence the learner was asked to read. */
    public double score(byte[] audio, String filename, String targetText) {
        return assess(audio, filename, targetText).accuracy();
    }

    public Heard assess(byte[] audio, String filename, String targetText) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("audio", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        form.add("target_text", targetText);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        // The service closes idle connections after a few seconds, so a reused one can fail before anything is sent.
        for (int attempt = 1; ; attempt++) {
            try {
                JsonNode body = scoring.postForObject(baseUrl + "/score-speech",
                        new org.springframework.http.HttpEntity<>(form, headers), JsonNode.class);
                if (body == null || !body.has("transcript")) {
                    throw new AppException("Dịch vụ chấm phát âm trả về dữ liệu không hợp lệ.", HttpStatus.BAD_GATEWAY);
                }
                // Whisper sometimes writes traditional characters; the sentence is simplified, so compare like with like.
                String transcript = ZhConverterUtil.toSimple(body.path("transcript").asText(""));
                return new Heard(SpeechAlignment.align(targetText, transcript).accuracy(), transcript);
            } catch (ResourceAccessException ex) {
                if (attempt < 2) continue;
                log.warn("Speech service failed: {}", ex.getMessage());
                throw unavailable();
            } catch (RestClientException ex) {
                log.warn("Speech service failed: {}", ex.getMessage());
                throw unavailable();
            }
        }
    }

    private static AppException unavailable() {
        return new AppException("Dịch vụ chấm phát âm chưa sẵn sàng. Hãy bỏ qua câu này hoặc thử lại.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static SimpleClientHttpRequestFactory factory(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return factory;
    }
}
