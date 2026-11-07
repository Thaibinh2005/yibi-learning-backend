package com.yibi.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One entry per word and level for the vocabulary lookup, held in memory so a search takes milliseconds.
 *
 * Repeated imports left up to eight records per word, many without a reading or a meaning, and some batches with a
 * wrong tone. So the reading shown is the one most records agree on, and the meaning comes from the first record that
 * has one, since later batches sometimes hold only a fragment of it. A word can still be found by any of its readings.
 */
@Component
public class VocabularyLookupIndex {

    private static final Duration REFRESH_AFTER = Duration.ofHours(1);

    private static final String ALL_RECORDS = """
            SELECT v.raw_record_id, v.simplified, v.traditional, v.level_code, readings.all_readings, sense.meaning_vi
            FROM hsk_vocabulary_records v
            JOIN LATERAL (
                SELECT string_agg(r.pinyin, '|' ORDER BY r.reading_order) AS all_readings
                FROM hsk_vocabulary_readings r WHERE r.raw_record_id = v.raw_record_id
            ) readings ON readings.all_readings IS NOT NULL
            LEFT JOIN LATERAL (
                SELECT string_agg(s.meaning, '; ' ORDER BY s.sense_order) AS meaning_vi
                FROM hsk_vocabulary_senses s
                WHERE s.raw_record_id = v.raw_record_id AND lower(s.language_code) = 'vi'
            ) sense ON TRUE
            ORDER BY v.raw_record_id
            """;

    private static final Comparator<Scored> CLOSEST_FIRST = Comparator.comparingInt(Scored::rank)
            .thenComparing(scored -> scored.entry().level(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(scored -> scored.entry().simplified().length())
            .thenComparing(scored -> scored.entry().simplified());

    /** One stored record. {@code readings} is the record's pinyin, separated by "|". */
    record Row(long id, String simplified, String traditional, String level, String readings, String meaning) {}

    /** {@code recordId} is the record whose meaning, part of speech and example are shown. */
    record Entry(long recordId, String simplified, String traditional, String level, String pinyin, String pinyinKeys,
                 String plainMeaning, String accentedMeaning) {}

    private record Scored(Entry entry, int rank) {}

    public record Hit(long recordId, String pinyin) {}

    private final JdbcTemplate jdbc;
    private volatile List<Entry> entries;
    private volatile Instant loadedAt = Instant.EPOCH;

    public VocabularyLookupIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Hit> find(String level, String search, int page, int size) {
        String wanted = level == null || level.isBlank() ? null : level.trim().matches("[789]") ? "7-9" : level.trim();
        VocabularySearchTerm term = search == null || search.isBlank() ? null : VocabularySearchTerm.of(search);
        return search(current(), wanted, term).stream()
                .skip((long) page * size).limit(size)
                .map(entry -> new Hit(entry.recordId(), entry.pinyin()))
                .toList();
    }

    private List<Entry> current() {
        if (entries == null || loadedAt.plus(REFRESH_AFTER).isBefore(Instant.now())) {
            synchronized (this) {
                if (entries == null || loadedAt.plus(REFRESH_AFTER).isBefore(Instant.now())) {
                    entries = build(jdbc.query(ALL_RECORDS, (rs, rowNum) -> new Row(rs.getLong("raw_record_id"), rs.getString("simplified"),
                            rs.getString("traditional"), rs.getString("level_code"), rs.getString("all_readings"), rs.getString("meaning_vi"))));
                    loadedAt = Instant.now();
                }
            }
        }
        return entries;
    }

    /** Rows are expected in record order, oldest first. */
    static List<Entry> build(List<Row> rows) {
        Map<String, Map<String, Integer>> readingsOfWord = new HashMap<>();
        Map<List<String>, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            readingsOfWord.computeIfAbsent(row.simplified(), word -> new HashMap<>()).merge(firstReading(row), 1, Integer::sum);
            groups.computeIfAbsent(java.util.Arrays.asList(row.simplified(), row.level()), key -> new ArrayList<>()).add(row);
        }
        List<Entry> built = new ArrayList<>(groups.size());
        for (List<Row> group : groups.values()) {
            Row meant = group.stream().filter(row -> row.meaning() != null).findFirst().orElse(group.get(0));
            Row first = group.get(0);
            String keys = group.stream().map(row -> pinyinKey(row.readings())).distinct().reduce("", (a, b) -> a + " " + b) + " ";
            String traditional = group.stream().map(Row::traditional).filter(Objects::nonNull).findFirst().orElse(null);
            built.add(new Entry(meant.id(), first.simplified(), traditional, first.level(), agreedReading(group, readingsOfWord.get(first.simplified())),
                    keys, meaningWords(meant.meaning(), false), meaningWords(meant.meaning(), true)));
        }
        return built;
    }

    /** Most common at this level, then across the word's levels; a tie goes to the newer record. */
    private static String agreedReading(List<Row> group, Map<String, Integer> acrossWord) {
        Map<String, Integer> atLevel = new HashMap<>();
        Map<String, Integer> lastSeen = new HashMap<>();
        for (int i = 0; i < group.size(); i++) {
            atLevel.merge(firstReading(group.get(i)), 1, Integer::sum);
            lastSeen.put(firstReading(group.get(i)), i);
        }
        return atLevel.keySet().stream()
                .max(Comparator.<String>comparingInt(atLevel::get).thenComparingInt(acrossWord::get).thenComparingInt(lastSeen::get))
                .orElseThrow();
    }

    private static String firstReading(Row row) {
        int end = row.readings().indexOf('|');
        return end < 0 ? row.readings() : row.readings().substring(0, end);
    }

    static List<Entry> search(List<Entry> entries, String level, VocabularySearchTerm term) {
        List<Scored> scored = new ArrayList<>();
        for (Entry entry : entries) {
            if (level != null && !level.equals(entry.level())) continue;
            int rank = term == null ? 0 : rank(entry, term);
            if (rank >= 0) scored.add(new Scored(entry, rank));
        }
        scored.sort(CLOSEST_FIRST);
        return scored.stream().map(Scored::entry).toList();
    }

    /** Smaller is closer: the word itself, its whole pinyin, one whole meaning, then what starts or contains it. -1 is no match. */
    private static int rank(Entry entry, VocabularySearchTerm term) {
        String simplified = entry.simplified();
        String traditional = entry.traditional() == null ? "" : entry.traditional();
        String meaning = term.accented() ? entry.accentedMeaning() : entry.plainMeaning();
        boolean pinyin = !term.pinyin().isEmpty();
        boolean word = !term.word().isEmpty();
        if (simplified.equals(term.text()) || traditional.equals(term.text())) return 0;
        if (pinyin && entry.pinyinKeys().contains(" " + term.pinyin() + " ")) return 1;
        if (word && meaning.contains(" ; " + term.word() + " ; ")) return 2;
        if (simplified.startsWith(term.text()) || (pinyin && entry.pinyinKeys().contains(" " + term.pinyin()))) return 3;
        if (word && meaning.contains(" " + term.word())) return 4;
        if (simplified.contains(term.text()) || traditional.contains(term.text())) return 5;
        return -1;
    }

    /** Each reading as bare letters; one reading can list several, as in "ě xīn, è xīn". */
    private static String pinyinKey(String readings) {
        StringBuilder keys = new StringBuilder();
        for (String reading : readings.split("[|,，]")) {
            keys.append(' ').append(VocabularySearchTerm.stripAccents(reading.toLowerCase(Locale.ROOT)).replaceAll("[^a-z]", ""));
        }
        return keys.substring(1);
    }

    /** The meanings as " ; first meaning ; second meaning ; ", so whole meanings and word starts can be matched. */
    private static String meaningWords(String meaning, boolean keepAccents) {
        if (meaning == null) return "";
        String lower = java.text.Normalizer.normalize(meaning, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String words = keepAccents ? lower : VocabularySearchTerm.stripAccents(lower);
        return " ; " + words.replaceAll("[\\p{Punct}\\s&&[^;]]+", " ").replaceAll(" ?; ?", " ; ").trim() + " ; ";
    }
}
