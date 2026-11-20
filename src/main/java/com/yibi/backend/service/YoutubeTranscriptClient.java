package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.yibi.backend.dto.video.VideoTranscriptDtos.TranscriptSegment;
import com.yibi.backend.dto.video.VideoTranscriptDtos.YoutubeLanguage;
import com.yibi.backend.dto.video.VideoTranscriptDtos.YoutubeLanguages;
import com.yibi.backend.dto.video.VideoTranscriptDtos.YoutubeTranscript;
import com.yibi.backend.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads YouTube subtitles through yibi-speech-service, which wraps youtube-transcript-api (no Google API key).
 * Errors keep the service's Vietnamese message so the admin knows whether to retry, pick another track or another video.
 */
@Slf4j
@Component
public class YoutubeTranscriptClient {

    private final String baseUrl;
    private final RestTemplate http;
    private final ObjectMapper mapper;

    public YoutubeTranscriptClient(@Value("${app.speech.base-url:http://localhost:8001}") String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(30_000);
        this.http = new RestTemplate(factory);
        this.mapper = mapper;
    }

    public YoutubeLanguages languages(String video) {
        JsonNode body = get(UriComponentsBuilder.fromHttpUrl(baseUrl + "/youtube/languages").queryParam("video", video));
        List<YoutubeLanguage> languages = new ArrayList<>();
        body.path("languages").forEach(l -> languages.add(new YoutubeLanguage(l.path("languageCode").asText(),
                l.path("language").asText(), l.path("isGenerated").asBoolean(), textOrNull(l.path("script")))));
        JsonNode generated = body.path("recommendedIsGenerated");
        return new YoutubeLanguages(body.path("videoId").asText(), languages, textOrNull(body.path("recommended")),
                generated.isBoolean() ? generated.asBoolean() : null);
    }

    public YoutubeTranscript transcript(String video, String language, Boolean generated) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/youtube/transcript").queryParam("video", video);
        if (language != null && !language.isBlank()) uri.queryParam("language", language);
        if (generated != null) uri.queryParam("generated", generated);
        JsonNode body = get(uri);
        List<TranscriptSegment> segments = new ArrayList<>();
        int traditional = 0;
        for (JsonNode s : body.path("segments")) {
            String text = s.path("text").asText();
            traditional += traditionalChars(text);
            segments.add(new TranscriptSegment(text, s.path("start").asDouble(), s.path("duration").asDouble(), s.path("end").asDouble()));
        }
        return new YoutubeTranscript(body.path("videoId").asText(), body.path("language").asText(),
                body.path("languageCode").asText(), body.path("isGenerated").asBoolean(), textOrNull(body.path("script")),
                segments, traditional);
    }

    /** Characters whose simplified form is different, i.e. written in traditional script. */
    static int traditionalChars(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN && !ch.equals(ZhConverterUtil.toSimple(ch))) count++;
            i += Character.charCount(cp);
        }
        return count;
    }

    private JsonNode get(UriComponentsBuilder uri) {
        try {
            JsonNode body = http.getForObject(uri.encode().build().toUri(), JsonNode.class);
            if (body == null) throw new AppException("Dịch vụ phụ đề trả về dữ liệu rỗng.", HttpStatus.BAD_GATEWAY);
            return body;
        } catch (HttpStatusCodeException ex) {
            throw fromService(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (RestClientException ex) {
            log.warn("Transcript service unreachable: {}", ex.getMessage());
            throw new AppException("Dịch vụ phụ đề (yibi-speech-service) chưa chạy. Hãy khởi động nó rồi thử lại.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    AppException fromService(int status, String body) {
        String message = null;
        try {
            message = textOrNull(mapper.readTree(body).path("detail").path("message"));
        } catch (Exception ignored) {
            // not our JSON, e.g. a proxy error page
        }
        if (message == null) message = "Không đọc được phụ đề từ YouTube. Hãy thử lại sau.";
        HttpStatus mapped = switch (status) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 404 -> HttpStatus.NOT_FOUND;
            case 403 -> HttpStatus.UNPROCESSABLE_ENTITY; // a 403 here is the video's restriction, not the admin's rights
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new AppException(message, mapped);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }
}
