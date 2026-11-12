package com.yibi.backend.service;

import com.yibi.backend.dto.srs.SrsWritingCardResponse;
import com.yibi.backend.dto.srs.SrsWritingRateRequest;
import com.yibi.backend.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Spaced repetition for characters a learner wrote badly, scheduled by the same rule as topic flashcards. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SrsWritingService {

    private static final BigDecimal DEFAULT_EASE_FACTOR = new BigDecimal("2.50");

    private final JdbcTemplate jdbc;
    private final HanziService hanziService;

    @Transactional(readOnly = true)
    public List<SrsWritingCardResponse> getDueCards(UUID userId) {
        return jdbc.query(SELECT + " WHERE c.user_id = ? AND c.due_date <= CURRENT_DATE ORDER BY c.due_date, c.id",
                SrsWritingService::map, userId);
    }

    @Transactional
    public SrsWritingCardResponse rate(UUID userId, SrsWritingRateRequest request) {
        String hanzi = request.getHanzi();
        if (!hanziService.exists(hanzi)) {
            throw new BadRequestException("Not an HSK writing character: " + hanzi);
        }
        int repetitions = 0;
        int intervalDays = 1;
        BigDecimal ease = DEFAULT_EASE_FACTOR;
        // The row is locked so two quick ratings of one character are applied one after the other.
        List<SrsWritingCardResponse> current = jdbc.query(SELECT + " WHERE c.user_id = ? AND c.hanzi = ? FOR UPDATE OF c",
                SrsWritingService::map, userId, hanzi);
        if (!current.isEmpty()) {
            SrsWritingCardResponse card = current.get(0);
            repetitions = card.getRepetition();
            intervalDays = card.getIntervalDays();
            ease = card.getEaseFactor();
        }

        SrsService.RatingSchedule next = SrsService.scheduleRating(request.getRating(), repetitions, intervalDays, ease);
        jdbc.update("""
                INSERT INTO srs_writing_cards (user_id, hanzi, ease_factor, interval_days, repetitions, due_date, last_reviewed)
                VALUES (?, ?, ?, ?, ?, CURRENT_DATE + ?::integer, LOCALTIMESTAMP)
                ON CONFLICT (user_id, hanzi) DO UPDATE SET
                  ease_factor = EXCLUDED.ease_factor, interval_days = EXCLUDED.interval_days,
                  repetitions = EXCLUDED.repetitions, due_date = EXCLUDED.due_date, last_reviewed = EXCLUDED.last_reviewed
                """, userId, hanzi, next.easeFactor(), next.intervalDays(), next.repetitions(),
                next.intervalDays());
        log.info("Writing SRS: userId={}, hanzi={}, rating={}, interval={}d", userId, hanzi, request.getRating(), next.intervalDays());
        return jdbc.query(SELECT + " WHERE c.user_id = ? AND c.hanzi = ?", SrsWritingService::map, userId, hanzi).get(0);
    }

    private static final String SELECT = """
            SELECT c.id, c.hanzi, c.ease_factor, c.interval_days, c.repetitions, c.due_date, c.last_reviewed,
                   (SELECT s.pinyin FROM hanzi_syllables s WHERE s.hanzi = c.hanzi ORDER BY s.id LIMIT 1) AS pinyin
            FROM srs_writing_cards c
            """;

    private static SrsWritingCardResponse map(ResultSet rs, int row) throws SQLException {
        return SrsWritingCardResponse.builder()
                .cardId(rs.getLong("id"))
                .hanzi(rs.getString("hanzi"))
                .pinyin(rs.getString("pinyin"))
                .easeFactor(rs.getBigDecimal("ease_factor"))
                .intervalDays(rs.getInt("interval_days"))
                .repetition(rs.getInt("repetitions"))
                .dueDate(rs.getDate("due_date").toLocalDate())
                .lastReviewed(rs.getTimestamp("last_reviewed") == null ? null : rs.getTimestamp("last_reviewed").toLocalDateTime())
                .build();
    }
}
