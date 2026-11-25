package com.yibi.backend.service.mocktest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Talks to yibi-speech-service about sample paper imports: quick checks of a PDF, queuing an import job (PDF +
 * recording, read and cut in the background), its progress and result, and re-cutting clips after an admin moved
 * their marks. Files are streamed, never held in memory.
 */
@Slf4j
@Component
public class MockTestSpeechClient {

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public MockTestSpeechClient(@Value("${app.speech.base-url:http://localhost:8001}") String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
    }

    /** One field of a multipart form: text, or a file streamed from a resource. */
    record Part(String name, String filename, String text, Resource file) {
        static Part text(String name, String value) {
            return new Part(name, null, value, null);
        }

        static Part file(String name, String filename, Resource file) {
            return new Part(name, filename, null, file);
        }
    }

    /** {pages, hasText, hasAnswers, level, supported, cover: {skills: {listening: {questions, minutes}}, totalMinutes}} */
    public JsonNode inspect(Resource pdf) {
        return json(send(multipart("/mocktests/inspect", Duration.ofMinutes(1), List.of(Part.file("pdf", "paper.pdf", pdf)))));
    }

    /** Queues an import; the job's id is ours (the import row's), so a retry replaces it. */
    public JsonNode submit(String jobId, Resource pdf, String pdfName, Resource audio, String audioName, Integer level) {
        List<Part> parts = new ArrayList<>(List.of(Part.text("job_id", jobId), Part.file("pdf", pdfName, pdf),
                Part.file("audio", audioName, audio)));
        if (level != null) parts.add(Part.text("level", level.toString()));
        return json(send(multipart("/mocktests/jobs", Duration.ofMinutes(10), parts)));
    }

    /** The job's state, or null when the service does not know it (restarted with a clean folder, or deleted). */
    public JsonNode status(String jobId) {
        HttpResponse<InputStream> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/mocktests/jobs/" + jobId))
                .timeout(Duration.ofSeconds(10)).GET().build(), false);
        if (response.statusCode() == 404) {
            close(response);
            return null;
        }
        return json(check(response));
    }

    /** Downloads the finished job's zip (exam.json, transcript.json, peaks.json, img/, audio/) to target. */
    public Path downloadResult(String jobId, Path target) {
        HttpResponse<InputStream> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/mocktests/jobs/" + jobId + "/result"))
                .timeout(Duration.ofMinutes(5)).GET().build(), true);
        return save(response, target);
    }

    public void delete(String jobId) {
        try {
            close(send(HttpRequest.newBuilder(URI.create(baseUrl + "/mocktests/jobs/" + jobId)).timeout(Duration.ofSeconds(10))
                    .DELETE().build(), false));
        } catch (AppException ex) {
            log.debug("Could not delete mock test job {}: {}", jobId, ex.getMessage());
        }
    }

    /** Cuts clips ([{key, start, end}]) from the recording; the zip holds one <key>.mp3 per clip. */
    public Path cut(Resource audio, String audioName, List<?> clips, Path target) {
        String list;
        try {
            list = mapper.writeValueAsString(clips);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return save(send(multipart("/mocktests/cut", Duration.ofMinutes(10),
                List.of(Part.file("audio", audioName, audio), Part.text("clips", list)))), target);
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private HttpRequest multipart(String path, Duration timeout, List<Part> parts) {
        String boundary = "----yibi" + UUID.randomUUID().toString().replace("-", "");
        List<HttpRequest.BodyPublisher> body = new ArrayList<>();
        for (Part part : parts) {
            StringBuilder head = new StringBuilder("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(part.name()).append('"');
            if (part.file() != null) {
                head.append("; filename=\"").append(part.filename().replaceAll("[\"\\r\\n]", "")).append('"')
                        .append("\r\nContent-Type: application/octet-stream");
            }
            head.append("\r\n\r\n");
            body.add(HttpRequest.BodyPublishers.ofString(head.toString(), StandardCharsets.UTF_8));
            if (part.file() != null) {
                Resource file = part.file();
                body.add(HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return file.getInputStream();
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }));
            } else {
                body.add(HttpRequest.BodyPublishers.ofString(part.text(), StandardCharsets.UTF_8));
            }
            body.add(HttpRequest.BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8));
        }
        body.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n", StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(body.toArray(HttpRequest.BodyPublisher[]::new)))
                .build();
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        return send(request, true);
    }

    private HttpResponse<InputStream> send(HttpRequest request, boolean checked) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | UncheckedIOException ex) {
            log.warn("Speech service call {} failed: {}", request.uri().getPath(), ex.getMessage());
            throw new AppException("Dịch vụ xử lý đề (yibi-speech-service) chưa chạy. Hãy bật dịch vụ rồi thử lại.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException("Yêu cầu bị ngắt.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return checked ? check(response) : response;
    }

    /** A 4xx/5xx becomes an error with the service's own message ("PDF không có lớp chữ…"). */
    private HttpResponse<InputStream> check(HttpResponse<InputStream> response) {
        if (response.statusCode() < 400) return response;
        String message = "";
        try (InputStream in = response.body()) {
            JsonNode body = mapper.readTree(in);
            message = body.path("detail").isTextual() ? body.path("detail").asText() : body.path("detail").path("message").asText("");
        } catch (IOException ignored) {
            // no JSON body
        }
        HttpStatus status = response.statusCode() >= 500 ? HttpStatus.BAD_GATEWAY
                : response.statusCode() == 413 ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.UNPROCESSABLE_ENTITY;
        throw new AppException(message.isBlank() ? "Dịch vụ xử lý đề báo lỗi " + response.statusCode() + "." : message, status);
    }

    private JsonNode json(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return mapper.readTree(in);
        } catch (IOException ex) {
            throw new AppException("Dịch vụ xử lý đề trả về dữ liệu không hợp lệ.", HttpStatus.BAD_GATEWAY);
        }
    }

    private static Path save(HttpResponse<InputStream> response, Path target) {
        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException ex) {
            throw new AppException("Không tải được kết quả từ dịch vụ xử lý đề.", HttpStatus.BAD_GATEWAY);
        }
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
