package com.yibi.backend.service;

import com.yibi.backend.dto.admin.AdminDtos.ServiceCheck;
import com.yibi.backend.dto.admin.AdminDtos.SystemStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The admin system page: Gemini keys and today's calls, the speech service, Redis and the database, stored media, and
 * the queues waiting for an admin. Each check answers within a few seconds and never throws.
 */
@Service
public class SystemStatusService {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final GeminiJsonClient gemini;
    private final GeminiUsageTracker usage;
    private final String speechUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SystemStatusService(JdbcTemplate jdbc, StringRedisTemplate redis, GeminiJsonClient gemini, GeminiUsageTracker usage,
                               @Value("${app.speech.base-url:http://localhost:8001}") String speechUrl) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.gemini = gemini;
        this.usage = usage;
        this.speechUrl = speechUrl.replaceAll("/+$", "");
    }

    public SystemStatus status() {
        List<ServiceCheck> services = new ArrayList<>();
        services.add(database());
        services.add(redisCheck());
        services.add(speech());
        services.add(new ServiceCheck("Gemini", gemini.keyCount() > 0,
                gemini.keyCount() > 0 ? gemini.keyCount() + " khoá đã cấu hình" : "Chưa cấu hình GEMINI_API_KEY"));
        return new SystemStatus(gemini.keyCount(), gemini.models(), usage.today(),
                number("SELECT COALESCE(sum(segments_translated), 0) FROM learner_daily_usage WHERE day = CURRENT_DATE"),
                number("SELECT count(*) FROM segment_translations"),
                services,
                number("SELECT count(*) FROM media_sources WHERE provider = 'UPLOAD'"),
                number("SELECT COALESCE(sum(media_bytes), 0) FROM media_sources WHERE provider = 'UPLOAD'"),
                number("SELECT count(*) FROM users"),
                number("SELECT count(*) FROM users WHERE last_login >= CURRENT_DATE"),
                number("SELECT count(*) FROM content_reports WHERE status = 'OPEN'"),
                number("SELECT count(*) FROM listening_lessons WHERE visibility = 'PENDING_REVIEW'"));
    }

    private long number(String sql) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private ServiceCheck database() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new ServiceCheck("PostgreSQL", true, "Kết nối bình thường");
        } catch (RuntimeException ex) {
            return new ServiceCheck("PostgreSQL", false, "Không kết nối được");
        }
    }

    private ServiceCheck redisCheck() {
        try {
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            return new ServiceCheck("Redis", "PONG".equalsIgnoreCase(pong), "Giới hạn tần suất, phiên đăng nhập, OTP");
        } catch (RuntimeException ex) {
            return new ServiceCheck("Redis", false, "Không kết nối được: giới hạn tần suất tạm tắt, đăng nhập có thể lỗi");
        }
    }

    private ServiceCheck speech() {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(speechUrl + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
            boolean up = r.statusCode() == 200;
            return new ServiceCheck("Speech service (Whisper)", up, up ? "Đang chạy" : "Trả lỗi HTTP " + r.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ServiceCheck("Speech service (Whisper)", false, "Không phản hồi");
        } catch (Exception ex) {
            return new ServiceCheck("Speech service (Whisper)", false,
                    "Không phản hồi: chấm phát âm, phụ đề YouTube và nhận dạng file đang tắt");
        }
    }
}
