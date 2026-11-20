package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.yibi.backend.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends a whole audio or video file to yibi-speech-service {@code POST /transcribe} and gets timed segments back.
 * The file is streamed, never held in memory; Whisper's traditional characters are turned into simplified ones.
 */
@Slf4j
@Component
public class SpeechTranscriptionClient {

    /** One character Whisper heard, with its time, so a line can be split exactly where the learner wants. */
    public record CharTime(String w, double start, double end) { }

    public record HeardSegment(String text, double start, double end, List<CharTime> timing) { }

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SpeechTranscriptionClient(@Value("${app.speech.base-url:http://localhost:8001}") String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
    }

    public List<HeardSegment> transcribe(Resource media, String filename, int maxSeconds) {
        String boundary = "----yibi" + UUID.randomUUID().toString().replace("-", "");
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"max_seconds\"\r\n\r\n" + maxSeconds + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media\"; filename=\"" + filename.replaceAll("[\"\\r\\n]", "") + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/transcribe"))
                .timeout(Duration.ofMinutes(40))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofString(head, StandardCharsets.UTF_8),
                        HttpRequest.BodyPublishers.ofInputStream(() -> {
                            try {
                                return media.getInputStream();
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        }),
                        HttpRequest.BodyPublishers.ofString(tail, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | UncheckedIOException ex) {
            log.warn("Transcription failed: {}", ex.getMessage());
            throw new AppException("Dịch vụ nhận dạng giọng nói (yibi-speech-service) chưa chạy. Hãy thử lại sau.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException("Nhận dạng bị ngắt.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            JsonNode body = mapper.readTree(response.body());
            if (response.statusCode() != 200) {
                String message = body.path("detail").path("message").asText("");
                throw new AppException(message.isBlank() ? "Không nhận dạng được file." : message, HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return parse(body);
        } catch (IOException ex) {
            throw new AppException("Dịch vụ nhận dạng trả về dữ liệu không hợp lệ.", HttpStatus.BAD_GATEWAY);
        }
    }

    static List<HeardSegment> parse(JsonNode body) {
        List<HeardSegment> out = new ArrayList<>();
        for (JsonNode s : body.path("segments")) {
            String text = ZhConverterUtil.toSimple(s.path("text").asText("")).trim();
            if (text.isEmpty()) continue;
            List<CharTime> timing = new ArrayList<>();
            for (JsonNode w : s.path("words")) {
                String word = ZhConverterUtil.toSimple(w.path("w").asText("")).trim();
                if (!word.isEmpty()) timing.add(new CharTime(word, w.path("start").asDouble(), w.path("end").asDouble()));
            }
            out.add(new HeardSegment(text, s.path("start").asDouble(), s.path("end").asDouble(), timing));
        }
        return out;
    }
}
