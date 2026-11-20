package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * A YouTube video's title and channel from the public oEmbed endpoint (no API key). Empty when it cannot be read.
 * The channel is shown as the credit for the video: the app never treats whoever pasted the link as its owner.
 */
@Slf4j
@Component
public class YoutubeTitleClient {

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public YoutubeTitleClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record YoutubeInfo(String title, String channelName, String channelUrl) { }

    public Optional<String> title(String videoId) {
        return info(videoId).map(YoutubeInfo::title).filter(t -> !t.isEmpty());
    }

    public Optional<YoutubeInfo> info(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return Optional.empty();
        String watch = "https://www.youtube.com/watch?v=" + videoId;
        URI uri = URI.create("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(watch, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return Optional.empty();
            JsonNode body = mapper.readTree(response.body());
            String channelUrl = body.path("author_url").asText("");
            return Optional.of(new YoutubeInfo(clean(body.path("title").asText("")),
                    blankToNull(clean(body.path("author_name").asText(""))),
                    channelUrl.startsWith("https://www.youtube.com/") && channelUrl.length() <= 300 ? channelUrl : null));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception ex) {
            log.debug("YouTube title for {} not available: {}", videoId, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Collapses whitespace and keeps at most 200 characters (the column's limit). */
    static String clean(String title) {
        String t = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        return t.length() <= 200 ? t : t.substring(0, 199).strip() + "…";
    }
}
