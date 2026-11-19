package com.yibi.backend.service;

import com.yibi.backend.dto.vocabulary.LevelMeaningResponse;
import com.yibi.backend.dto.vocabulary.WordDetailsDtos.Card;
import com.yibi.backend.dto.vocabulary.WordDetailsDtos.CharacterInfo;
import com.yibi.backend.dto.vocabulary.WordDetailsDtos.DeckMembership;
import com.yibi.backend.dto.vocabulary.WordDetailsDtos.Example;
import com.yibi.backend.dto.vocabulary.WordDetailsDtos.RelatedWord;
import com.yibi.backend.dto.vocabulary.WordDetailsDtos.Topic;
import com.yibi.backend.dto.vocabulary.WordDetailsDtos.WordDetailsResponse;
import com.yibi.backend.dto.vocabulary.WordDetailsDtos.WordStudyResponse;
import com.yibi.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the word detail modal reads, all from data already stored: example sentences, words sharing a
 * character, topics, and the learner's flashcard and decks. Nothing is generated or written here.
 */
@Service
@RequiredArgsConstructor
public class WordDetailsService {

    static final int MAX_EXAMPLES = 8;
    static final int MAX_RELATED_PER_CHARACTER = 8;
    private static final int MAX_MEANING_LENGTH = 60;
    private static final String CJK = "[一-鿿]";

    private final JdbcTemplate jdbc;
    private final LevelMeaningService levelMeanings;

    /** A record's word without the homograph digit some sources add (会1), and its level. */
    private record Word(String simplified, String plain, String level) {}

    private Word word(long vocabularyId) {
        return jdbc.query("SELECT simplified, level_code FROM hsk_vocabulary_records WHERE raw_record_id = ?",
                        (rs, n) -> new Word(rs.getString(1), rs.getString(1).replaceAll("[0-9]+$", ""), rs.getString(2)), vocabularyId)
                .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Vocabulary", "id", vocabularyId));
    }

    @Transactional(readOnly = true)
    public WordDetailsResponse details(long vocabularyId) {
        Word word = word(vocabularyId);
        return new WordDetailsResponse(vocabularyId, examples(word), characters(word), relatedWords(word), topics(word));
    }

    /**
     * Stored sentences containing the word with a clean Vietnamese translation: the one written for the meaning of
     * this level first, then others of this level, then the other levels from the lowest.
     */
    private List<Example> examples(Word word) {
        List<Example> found = jdbc.query("""
                SELECT e.sentence_zh, e.sentence_pinyin, e.translation_vi, e.audio_url, ev.level_code,
                       (e.source = 'level_meaning' AND ev.level_code IS NOT DISTINCT FROM ?) AS for_level
                FROM hsk_vocabulary_examples e
                JOIN hsk_vocabulary_records ev ON ev.raw_record_id = e.raw_record_id
                WHERE ev.simplified = ? AND position(? IN e.sentence_zh) > 0
                  AND COALESCE(e.translation_vi, '') <> '' AND e.translation_vi !~ ?
                ORDER BY for_level DESC, (ev.level_code IS NOT DISTINCT FROM ?) DESC, ev.level_code NULLS LAST, e.id
                """, (rs, n) -> new Example(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBoolean(6)), word.level(), word.simplified(), word.plain(), CJK, word.level());
        Map<String, Example> unique = new LinkedHashMap<>();
        for (Example example : found) unique.putIfAbsent(example.zh().trim(), example);
        return unique.values().stream().limit(MAX_EXAMPLES).toList();
    }

    /** One HSK record per (word, level) with its first reading and Vietnamese meaning. */
    private record BankWord(long id, String simplified, String traditional, String level, String pinyin, String meaning) {}

    private static final String BANK_WORD = """
            SELECT DISTINCT ON (v.simplified, v.level_code)
                   v.raw_record_id, v.simplified, v.traditional, v.level_code, rd.pinyin, sn.meaning_vi
            FROM hsk_vocabulary_records v
            JOIN LATERAL (
                SELECT r.pinyin FROM hsk_vocabulary_readings r
                WHERE r.raw_record_id = v.raw_record_id ORDER BY r.reading_order LIMIT 1
            ) rd ON TRUE
            JOIN LATERAL (
                SELECT string_agg(s.meaning, '; ' ORDER BY s.sense_order) AS meaning_vi
                FROM hsk_vocabulary_senses s
                WHERE s.raw_record_id = v.raw_record_id AND lower(s.language_code) = 'vi'
            ) sn ON sn.meaning_vi IS NOT NULL
            """;

    private BankWord bankWord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BankWord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
    }

    /** Each distinct character of the word, with its lowest-level HSK entry when the character is a word itself. */
    private List<CharacterInfo> characters(Word word) {
        List<CharacterInfo> result = new ArrayList<>();
        for (String ch : distinctCharacters(word.plain())) {
            List<BankWord> entries = jdbc.query(BANK_WORD + """
                    WHERE regexp_replace(v.simplified, '[0-9]+$', '') = ?
                    ORDER BY v.simplified, v.level_code NULLS LAST, v.raw_record_id
                    """, (rs, n) -> bankWord(rs), ch);
            BankWord lowest = entries.stream()
                    .min(java.util.Comparator.comparing(BankWord::level, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                    .orElse(null);
            String traditional = traditionalOf(ch, word);
            result.add(lowest == null
                    ? new CharacterInfo(ch, traditional, null, null, null, null)
                    : new CharacterInfo(ch, lowest.traditional() != null ? lowest.traditional() : traditional, lowest.id(),
                            lowest.pinyin(), meaningAt(lowest), lowest.level()));
        }
        return result;
    }

    /** The traditional form of one character, read from the word's own traditional spelling when it has the same length. */
    private String traditionalOf(String ch, Word word) {
        String traditional = jdbc.query("SELECT traditional FROM hsk_vocabulary_records WHERE simplified = ? AND traditional IS NOT NULL LIMIT 1",
                (rs, n) -> rs.getString(1), word.simplified()).stream().findFirst().orElse(null);
        if (traditional == null) return null;
        int[] simple = word.plain().codePoints().toArray();
        int[] full = traditional.codePoints().toArray();
        if (simple.length != full.length) return null;
        for (int i = 0; i < simple.length; i++) {
            if (new String(Character.toChars(simple[i])).equals(ch)) return new String(Character.toChars(full[i]));
        }
        return null;
    }

    /** Words of the bank containing one of the word's characters: lowest level and shortest first, a few per character. */
    private List<RelatedWord> relatedWords(Word word) {
        List<RelatedWord> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(word.plain());
        for (String ch : distinctCharacters(word.plain())) {
            List<BankWord> containing = jdbc.query(BANK_WORD + """
                    WHERE position(? IN v.simplified) > 0
                    ORDER BY v.simplified, v.level_code NULLS LAST, v.raw_record_id
                    """, (rs, n) -> bankWord(rs), ch);
            containing.sort(java.util.Comparator.comparing(BankWord::level, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                    .thenComparingInt(w -> w.simplified().length()).thenComparing(BankWord::simplified));
            int taken = 0;
            for (BankWord other : containing) {
                String plain = other.simplified().replaceAll("[0-9]+$", "");
                if (taken == MAX_RELATED_PER_CHARACTER) break;
                if (plain.equals(ch) || !seen.add(plain)) continue;
                result.add(new RelatedWord(other.id(), other.simplified(), other.pinyin(), meaningAt(other), other.level(), ch));
                taken++;
            }
        }
        return result;
    }

    private List<Topic> topics(Word word) {
        return jdbc.query("""
                SELECT DISTINCT t.slug, t.title_vi, t.icon
                FROM vocabulary_topics vt
                JOIN topics t ON t.id = vt.topic_id
                JOIN hsk_vocabulary_records v ON v.raw_record_id = vt.raw_record_id
                WHERE v.simplified = ?
                ORDER BY t.title_vi
                """, (rs, n) -> new Topic(rs.getString(1), rs.getString(2), rs.getString(3)), word.simplified());
    }

    /** The meaning its level teaches when the official syllabus has it, else the first dictionary meaning, short. */
    private String meaningAt(BankWord word) {
        return levelMeanings.find(word.simplified(), word.level(), word.pinyin())
                .map(LevelMeaningResponse::getMain)
                .orElseGet(() -> shortMeaning(word.meaning()));
    }

    static String shortMeaning(String meaning) {
        if (meaning == null) return null;
        String first = meaning.split("[;；]")[0].trim();
        return first.length() > MAX_MEANING_LENGTH ? first.substring(0, MAX_MEANING_LENGTH - 1).trim() + "…" : first;
    }

    static List<String> distinctCharacters(String word) {
        Set<String> chars = new LinkedHashSet<>();
        word.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .forEach(cp -> chars.add(new String(Character.toChars(cp))));
        return new ArrayList<>(chars);
    }

    // ── The learner's state of a word ──────────────────────────

    @Transactional(readOnly = true)
    public WordStudyResponse study(UUID userId, long vocabularyId) {
        Word word = word(vocabularyId);
        LocalDate today = LocalDate.now();
        Card card = jdbc.query("""
                SELECT c.repetitions, c.interval_days, c.ease_factor, c.due_date, c.last_reviewed
                FROM srs_cards c JOIN hsk_vocabulary_records cv ON cv.raw_record_id = c.hsk_vocabulary_record_id
                WHERE c.user_id = ? AND cv.simplified = ?
                ORDER BY (c.hsk_vocabulary_record_id = ?) DESC, c.id
                LIMIT 1
                """, (rs, n) -> {
            int repetitions = rs.getInt("repetitions");
            var last = rs.getTimestamp("last_reviewed");
            LocalDate due = rs.getDate("due_date").toLocalDate();
            String status = last == null ? "NEW" : repetitions >= VocabDeckService.MASTERED_REPETITIONS ? "MASTERED" : "LEARNING";
            return new Card(status, repetitions, rs.getInt("interval_days"), rs.getBigDecimal("ease_factor"), due,
                    VocabDeckService.fromUtc(last), !due.isAfter(today));
        }, userId, word.simplified(), vocabularyId).stream().findFirst().orElse(null);
        List<DeckMembership> decks = jdbc.query("""
                SELECT d.id, d.name, EXISTS (
                    SELECT 1 FROM vocab_deck_words w JOIN hsk_vocabulary_records v ON v.raw_record_id = w.hsk_vocabulary_record_id
                    WHERE w.deck_id = d.id AND v.simplified = ? AND v.level_code IS NOT DISTINCT FROM ?) AS contains
                FROM vocab_decks d WHERE d.user_id = ?
                ORDER BY d.updated_at DESC, d.id DESC
                """, (rs, n) -> new DeckMembership(rs.getLong(1), rs.getString(2), rs.getBoolean(3)),
                word.simplified(), word.level(), userId);
        return new WordStudyResponse(vocabularyId, card, decks);
    }
}
