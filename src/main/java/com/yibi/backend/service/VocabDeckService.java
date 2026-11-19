package com.yibi.backend.service;

import com.yibi.backend.dto.deck.DeckDtos.DeckChangeResult;
import com.yibi.backend.dto.deck.DeckDtos.DeckResponse;
import com.yibi.backend.dto.deck.DeckDtos.DeckWordPage;
import com.yibi.backend.dto.deck.DeckDtos.DeckWordResponse;
import com.yibi.backend.dto.deck.DeckDtos.MemoryStatus;
import com.yibi.backend.dto.deck.DeckDtos.TransferMode;
import com.yibi.backend.dto.vocabulary.LevelMeaningResponse;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Personal vocabulary decks: a learner groups words of the HSK bank under names of their own. A deck only labels
 * words; the memory of a word is the learner's one flashcard for it (srs_cards, matched by the word as the flashcards
 * do), so a word in several decks is learnt once. Adding a word to a deck gives it a flashcard if it has none, so it
 * comes up in the review; removing it or deleting the deck keeps the flashcard and its progress.
 */
@Service
@RequiredArgsConstructor
public class VocabDeckService {

    static final int MAX_DECKS = 50;
    static final int MAX_WORDS_PER_DECK = 2000;
    /** As in the flashcard stats: answered right three times in a row. */
    static final int MASTERED_REPETITIONS = 3;
    private static final int MAX_MEANING_LENGTH = 80;

    private final JdbcTemplate jdbc;
    private final LevelMeaningService levelMeanings;
    private final VocabularyLookupIndex lookupIndex;

    /** The learner's flashcard for the word of record v: the same record first, else any record of the same word. */
    private static final String CARD_OF_WORD = """
            LEFT JOIN LATERAL (
                SELECT c.repetitions, c.interval_days, c.due_date, c.last_reviewed
                FROM srs_cards c JOIN hsk_vocabulary_records cv ON cv.raw_record_id = c.hsk_vocabulary_record_id
                WHERE c.user_id = d.user_id AND cv.simplified = v.simplified
                ORDER BY (c.hsk_vocabulary_record_id = v.raw_record_id) DESC, c.id
                LIMIT 1
            ) c ON TRUE
            """;

    private static final String DECKS = """
            SELECT d.id, d.name, d.created_at, d.updated_at,
                   count(w.deck_id) AS total,
                   count(*) FILTER (WHERE c.last_reviewed IS NOT NULL) AS learned,
                   count(*) FILTER (WHERE c.repetitions >= %d) AS mastered,
                   count(*) FILTER (WHERE c.due_date <= CURRENT_DATE) AS due
            FROM vocab_decks d
            LEFT JOIN vocab_deck_words w ON w.deck_id = d.id
            LEFT JOIN hsk_vocabulary_records v ON v.raw_record_id = w.hsk_vocabulary_record_id
            %s
            WHERE d.user_id = ? %s
            GROUP BY d.id
            ORDER BY d.updated_at DESC, d.id DESC
            """;

    private static final String WORDS = """
            SELECT w.hsk_vocabulary_record_id AS id, v.simplified, v.level_code, w.added_at, rd.pinyin, sense.meaning_vi,
                   c.repetitions, c.interval_days, c.due_date, c.last_reviewed
            FROM vocab_decks d
            JOIN vocab_deck_words w ON w.deck_id = d.id
            JOIN hsk_vocabulary_records v ON v.raw_record_id = w.hsk_vocabulary_record_id
            LEFT JOIN LATERAL (
                SELECT r.pinyin FROM hsk_vocabulary_readings r
                WHERE r.raw_record_id = v.raw_record_id ORDER BY r.reading_order LIMIT 1
            ) rd ON TRUE
            LEFT JOIN LATERAL (
                SELECT string_agg(s.meaning, '; ' ORDER BY s.sense_order) AS meaning_vi
                FROM hsk_vocabulary_senses s
                WHERE s.raw_record_id = v.raw_record_id AND lower(s.language_code) = 'vi'
            ) sense ON TRUE
            """ + CARD_OF_WORD + """
            WHERE d.id = ? AND d.user_id = ?
            """;

    // ── Decks ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DeckResponse> listDecks(UUID userId) {
        return jdbc.query(DECKS.formatted(MASTERED_REPETITIONS, CARD_OF_WORD, ""), this::mapDeck, userId);
    }

    @Transactional
    public DeckResponse createDeck(UUID userId, String name) {
        String clean = cleanName(name);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM vocab_decks WHERE user_id = ?", Integer.class, userId);
        if (count != null && count >= MAX_DECKS) {
            throw new BadRequestException("Bạn đã có tối đa " + MAX_DECKS + " bộ từ vựng");
        }
        Long id;
        try {
            id = jdbc.queryForObject("INSERT INTO vocab_decks (user_id, name) VALUES (?, ?) RETURNING id", Long.class, userId, clean);
        } catch (DuplicateKeyException e) {
            throw duplicateName(clean);
        }
        return deck(userId, id);
    }

    @Transactional
    public DeckResponse renameDeck(UUID userId, long deckId, String name) {
        String clean = cleanName(name);
        try {
            int updated = jdbc.update("UPDATE vocab_decks SET name = ?, updated_at = now() WHERE id = ? AND user_id = ?",
                    clean, deckId, userId);
            if (updated == 0) throw notFound(deckId);
        } catch (DuplicateKeyException e) {
            throw duplicateName(clean);
        }
        return deck(userId, deckId);
    }

    /** Deletes the deck and its list of words; the flashcards of the words and their progress stay. */
    @Transactional
    public void deleteDeck(UUID userId, long deckId) {
        if (jdbc.update("DELETE FROM vocab_decks WHERE id = ? AND user_id = ?", deckId, userId) == 0) throw notFound(deckId);
    }

    @Transactional(readOnly = true)
    public DeckResponse deck(UUID userId, long deckId) {
        return jdbc.query(DECKS.formatted(MASTERED_REPETITIONS, CARD_OF_WORD, "AND d.id = ?"), this::mapDeck, userId, deckId)
                .stream().findFirst().orElseThrow(() -> notFound(deckId));
    }

    // ── Words ──────────────────────────────────────────────────

    /** Filters of the word list; null or blank means no filter. */
    public record WordQuery(String search, String level, String topic, String status, String sort, String direction,
                            int page, int size) {}

    @Transactional(readOnly = true)
    public DeckWordPage words(UUID userId, long deckId, WordQuery query) {
        DeckResponse deck = deck(userId, deckId);
        Map<String, String> readings = lookupIndex.readings();
        List<Row> rows = jdbc.query(WORDS, (rs, n) -> mapRow(rs), deckId, userId);
        List<Long> memberIds = rows.stream().map(Row::id).toList();

        Set<String> searched = blank(query.search()) ? null : lookupIndex.matchingWords(query.search().trim());
        String level = blank(query.level()) ? null : query.level().trim().matches("[789]") ? "7-9" : query.level().trim();
        Set<String> topicWords = blank(query.topic()) ? null : topicWords(query.topic().trim());
        String status = blank(query.status()) ? null : query.status().trim().toUpperCase(Locale.ROOT);

        LocalDate today = LocalDate.now();
        List<DeckWordResponse> filtered = new ArrayList<>();
        for (Row row : rows) {
            String key = row.simplified() + "|" + row.level();
            if (searched != null && !searched.contains(key) && !row.simplified().contains(query.search().trim())) continue;
            if (level != null && !level.equals(row.level())) continue;
            if (topicWords != null && !topicWords.contains(row.simplified())) continue;
            DeckWordResponse word = toResponse(row, readings.getOrDefault(key, row.pinyin()), today);
            if (status != null && !matchesStatus(word, status)) continue;
            filtered.add(word);
        }
        filtered.sort(order(query.sort(), query.direction()));

        int size = Math.min(Math.max(query.size(), 1), 200);
        int page = Math.max(query.page(), 0);
        List<DeckWordResponse> items = filtered.stream().skip((long) page * size).limit(size).toList();
        return new DeckWordPage(deck, items, filtered.size(), page, size, memberIds);
    }

    /**
     * Adds words of the HSK bank to a deck. A word already in the deck at the same level is skipped; each new word
     * gets a flashcard unless the learner already has one for it.
     */
    @Transactional
    public DeckChangeResult addWords(UUID userId, long deckId, List<Long> vocabularyIds) {
        deck(userId, deckId);
        DeckChangeResult result = insertWords(userId, deckId, new LinkedHashSet<>(vocabularyIds));
        touch(deckId);
        return result;
    }

    /** Takes words out of a deck; their flashcards and progress stay. */
    @Transactional
    public DeckChangeResult removeWords(UUID userId, long deckId, List<Long> vocabularyIds) {
        deck(userId, deckId);
        int removed = deleteWords(deckId, vocabularyIds);
        touch(deckId);
        return new DeckChangeResult(removed, Math.max(0, vocabularyIds.size() - removed));
    }

    /**
     * Copies words of a deck into another deck of the learner, or moves them (they leave the first deck). Words the
     * target already has are skipped, and when moving they still leave the first deck.
     */
    @Transactional
    public DeckChangeResult transfer(UUID userId, long deckId, long targetDeckId, TransferMode mode, List<Long> vocabularyIds) {
        if (deckId == targetDeckId) throw new BadRequestException("Bộ đích phải khác bộ hiện tại");
        deck(userId, deckId);
        deck(userId, targetDeckId);
        Set<Long> inSource = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT hsk_vocabulary_record_id FROM vocab_deck_words WHERE deck_id = ? AND hsk_vocabulary_record_id = ANY (?)",
                Long.class, deckId, idArray(vocabularyIds)));
        if (inSource.isEmpty()) throw new BadRequestException("Các từ đã chọn không có trong bộ này");
        DeckChangeResult copied = insertWords(userId, targetDeckId, inSource);
        if (mode == TransferMode.MOVE) {
            deleteWords(deckId, new ArrayList<>(inSource));
            touch(deckId);
        }
        touch(targetDeckId);
        return copied;
    }

    // ── Helpers ────────────────────────────────────────────────

    private DeckChangeResult insertWords(UUID userId, long deckId, Set<Long> vocabularyIds) {
        Integer size = jdbc.queryForObject("SELECT count(*) FROM vocab_deck_words WHERE deck_id = ?", Integer.class, deckId);
        int count = size == null ? 0 : size;
        int added = 0;
        int skipped = 0;
        for (Long id : vocabularyIds) {
            List<String[]> word = jdbc.query("SELECT simplified, level_code FROM hsk_vocabulary_records WHERE raw_record_id = ?",
                    (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, id);
            if (word.isEmpty()) throw new ResourceNotFoundException("Vocabulary", "id", id);
            Boolean present = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM vocab_deck_words w JOIN hsk_vocabulary_records v ON v.raw_record_id = w.hsk_vocabulary_record_id
                        WHERE w.deck_id = ? AND v.simplified = ? AND v.level_code IS NOT DISTINCT FROM ?)
                    """, Boolean.class, deckId, word.get(0)[0], word.get(0)[1]);
            if (Boolean.TRUE.equals(present)) {
                skipped++;
                continue;
            }
            if (count >= MAX_WORDS_PER_DECK) {
                throw new BadRequestException("Mỗi bộ có tối đa " + MAX_WORDS_PER_DECK + " từ");
            }
            jdbc.update("INSERT INTO vocab_deck_words (deck_id, hsk_vocabulary_record_id) VALUES (?, ?) ON CONFLICT DO NOTHING", deckId, id);
            // A flashcard due today, unless the learner already has one for the same word.
            jdbc.update("""
                    INSERT INTO srs_cards (user_id, hsk_vocabulary_record_id)
                    SELECT ?, ?
                    WHERE NOT EXISTS (
                        SELECT 1 FROM srs_cards c JOIN hsk_vocabulary_records cv ON cv.raw_record_id = c.hsk_vocabulary_record_id
                        WHERE c.user_id = ? AND cv.simplified = ?)
                    ON CONFLICT DO NOTHING
                    """, userId, id, userId, word.get(0)[0]);
            count++;
            added++;
        }
        return new DeckChangeResult(added, skipped);
    }

    /** Removes the given words, matched as the deck matches them: the same word at the same level, whatever record. */
    private int deleteWords(long deckId, List<Long> vocabularyIds) {
        return jdbc.update("""
                DELETE FROM vocab_deck_words w
                USING hsk_vocabulary_records v, hsk_vocabulary_records t
                WHERE w.deck_id = ? AND v.raw_record_id = w.hsk_vocabulary_record_id
                  AND t.raw_record_id = ANY (?) AND v.simplified = t.simplified
                  AND v.level_code IS NOT DISTINCT FROM t.level_code
                """, deckId, idArray(vocabularyIds));
    }

    private void touch(long deckId) {
        jdbc.update("UPDATE vocab_decks SET updated_at = now() WHERE id = ?", deckId);
    }

    private Array idArray(List<Long> ids) {
        return jdbc.execute((java.sql.Connection c) -> c.createArrayOf("bigint", ids.toArray()));
    }

    private Set<String> topicWords(String slug) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT v.simplified FROM topics t
                JOIN vocabulary_topics vt ON vt.topic_id = t.id
                JOIN hsk_vocabulary_records v ON v.raw_record_id = vt.raw_record_id
                WHERE t.slug = ?
                """, String.class, slug));
    }

    private record Row(long id, String simplified, String level, LocalDateTime addedAt, String pinyin, String meaning,
                       Integer repetitions, Integer intervalDays, LocalDate dueDate, LocalDateTime lastReviewed) {}

    private Row mapRow(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("simplified"), rs.getString("level_code"), time(rs.getTimestamp("added_at")),
                rs.getString("pinyin"), rs.getString("meaning_vi"), (Integer) rs.getObject("repetitions"),
                (Integer) rs.getObject("interval_days"), rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                fromUtc(rs.getTimestamp("last_reviewed")));
    }

    private DeckWordResponse toResponse(Row row, String pinyin, LocalDate today) {
        int repetitions = row.repetitions() == null ? 0 : row.repetitions();
        MemoryStatus status = row.lastReviewed() == null ? MemoryStatus.NEW
                : repetitions >= MASTERED_REPETITIONS ? MemoryStatus.MASTERED : MemoryStatus.LEARNING;
        String meaning = levelMeanings.find(row.simplified(), row.level(), pinyin)
                .map(LevelMeaningResponse::getMain)
                .orElseGet(() -> shortMeaning(row.meaning()));
        return new DeckWordResponse(row.id(), row.simplified(), pinyin, meaning, row.level(), row.addedAt(), row.lastReviewed(),
                row.dueDate(), repetitions, row.intervalDays() == null ? 0 : row.intervalDays(), status,
                row.dueDate() != null && !row.dueDate().isAfter(today));
    }

    static boolean matchesStatus(DeckWordResponse word, String status) {
        return switch (status) {
            case "DUE" -> word.due();
            case "LEARNED" -> word.status() != MemoryStatus.NEW;
            default -> word.status().name().equals(status);
        };
    }

    /**
     * added (default, newest first), reviewed (last review, newest first; never reviewed last) or memory (weakest
     * first: new, then learning by repetitions and interval, then mastered). "asc"/"desc" turns the order around.
     */
    static Comparator<DeckWordResponse> order(String sort, String direction) {
        String key = sort == null ? "added" : sort.trim().toLowerCase(Locale.ROOT);
        Comparator<DeckWordResponse> byWord = Comparator.comparing(DeckWordResponse::hanzi);
        Comparator<DeckWordResponse> order = switch (key) {
            case "reviewed" -> {
                Comparator<DeckWordResponse> last = Comparator.comparing(DeckWordResponse::lastReviewed,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
                yield "asc".equalsIgnoreCase(direction)
                        ? Comparator.comparing(DeckWordResponse::lastReviewed, Comparator.nullsLast(Comparator.naturalOrder()))
                        : last.reversed();
            }
            case "memory" -> {
                Comparator<DeckWordResponse> weakest = Comparator.comparing(DeckWordResponse::status)
                        .thenComparingInt(DeckWordResponse::repetitions)
                        .thenComparingInt(DeckWordResponse::intervalDays);
                yield "desc".equalsIgnoreCase(direction) ? weakest.reversed() : weakest;
            }
            default -> {
                Comparator<DeckWordResponse> added = Comparator.comparing(DeckWordResponse::addedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
                yield "asc".equalsIgnoreCase(direction) ? added : added.reversed();
            }
        };
        return order.thenComparing(byWord);
    }

    private static String shortMeaning(String meaning) {
        if (meaning == null) return null;
        String first = meaning.split("[;；]")[0].trim();
        return first.length() > MAX_MEANING_LENGTH ? first.substring(0, MAX_MEANING_LENGTH - 1).trim() + "…" : first;
    }

    private DeckResponse mapDeck(ResultSet rs, int n) throws SQLException {
        return new DeckResponse(rs.getLong("id"), rs.getString("name"), time(rs.getTimestamp("created_at")),
                time(rs.getTimestamp("updated_at")), rs.getLong("total"), rs.getLong("learned"), rs.getLong("mastered"),
                rs.getLong("due"));
    }

    private static LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * srs_cards.last_reviewed is written by JPA with hibernate.jdbc.time_zone = UTC into a column without a time
     * zone, so it holds UTC wall time; read back here as the server's local time, as the JPA reads give it.
     */
    static LocalDateTime fromUtc(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().atZone(java.time.ZoneOffset.UTC)
                .withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) throw new BadRequestException("Tên bộ từ vựng không được để trống");
        if (clean.length() > 80) throw new BadRequestException("Tên bộ từ vựng tối đa 80 ký tự");
        return clean;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResourceNotFoundException notFound(long deckId) {
        return new ResourceNotFoundException("Deck", "id", deckId);
    }

    private static BadRequestException duplicateName(String name) {
        return new BadRequestException("Bạn đã có bộ từ vựng tên \"" + name + "\"");
    }
}
