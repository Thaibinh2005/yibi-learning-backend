package com.yibi.backend.service;

import com.yibi.backend.dto.hanzi.HanziSyllableResponse;
import com.yibi.backend.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HanziService {

    private static final int MAX_RESULTS = 200;

    private final JdbcTemplate jdbc;

    /**
     * Writing characters for a syllable. A null filter matches anything; an empty initial means no initial
     * (a, ai, er). At least one filter is required so the whole table is never returned.
     */
    @Transactional(readOnly = true)
    public List<HanziSyllableResponse> bySyllable(String initial, String finalSound, Integer tone) {
        if (initial == null && finalSound == null && tone == null) {
            throw new BadRequestException("Give at least one of initial, final or tone");
        }
        if (tone != null && (tone < 1 || tone > 5)) {
            throw new BadRequestException("Tone must be 1 to 5");
        }
        finalSound = normaliseFinal(initial, finalSound);
        StringBuilder sql = new StringBuilder("SELECT hanzi, pinyin, level_code FROM hanzi_syllables WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (initial != null) { sql.append(" AND initial = ?"); args.add(initial); }
        if (finalSound != null) { sql.append(" AND final_sound = ?"); args.add(finalSound); }
        if (tone != null) { sql.append(" AND tone = ?"); args.add(tone); }
        sql.append(" ORDER BY level_code, hanzi LIMIT ").append(MAX_RESULTS);
        return jdbc.query(sql.toString(), (rs, i) -> map(rs.getString(1), rs.getString(2), rs.getString(3)), args.toArray());
    }

    /** All writing characters, optionally of one level ("1" to "6", "7-9", "1-2"); the first reading of each. */
    /** Accepts v for ü, and u after j, q, x, where the final is stored as ü. */
    private static String normaliseFinal(String initial, String finalSound) {
        if (finalSound == null) return null;
        String f = finalSound.replace('v', 'ü');
        boolean palatal = "j".equals(initial) || "q".equals(initial) || "x".equals(initial);
        return palatal && f.startsWith("u") ? "ü" + f.substring(1) : f;
    }

    @Transactional(readOnly = true)
    public List<HanziSyllableResponse> writing(String level) {
        String sql = "SELECT DISTINCT ON (hanzi) hanzi, pinyin, level_code FROM hanzi_syllables"
                + (level == null ? "" : " WHERE level_code = ?") + " ORDER BY hanzi, id";
        return level == null
                ? jdbc.query(sql, (rs, i) -> map(rs.getString(1), rs.getString(2), rs.getString(3)))
                : jdbc.query(sql, (rs, i) -> map(rs.getString(1), rs.getString(2), rs.getString(3)), level);
    }

    @Transactional(readOnly = true)
    public boolean exists(String hanzi) {
        Boolean found = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM hanzi_syllables WHERE hanzi = ?)", Boolean.class, hanzi);
        return Boolean.TRUE.equals(found);
    }

    private static HanziSyllableResponse map(String hanzi, String pinyin, String level) {
        return HanziSyllableResponse.builder().hanzi(hanzi).pinyin(pinyin).levelCode(level).build();
    }
}
