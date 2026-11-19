package com.yibi.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yibi.backend.dto.vocabulary.LevelMeaningResponse;
import com.yibi.backend.util.PartOfSpeechVi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The level meanings of data/hsk_level_meanings.json (made with generate_level_meanings.py). Only the records
 * marked official are used: the official syllabus has the word at that level with that pinyin. A word, level and
 * pinyin it does not find keeps the dictionary meaning the caller already has.
 */
@Slf4j
@Service
public class LevelMeaningService {

    static final String RESOURCE = "data/hsk_level_meanings.json";
    /** The HSK levels in teaching order. */
    static final List<String> LEVELS = List.of("1", "2", "3", "4", "5", "6", "7-9");

    private final String resource;
    private volatile Map<String, List<LevelMeaningResponse>> byWordAndLevel;

    public LevelMeaningService() {
        this(RESOURCE);
    }

    LevelMeaningService(String resource) {
        this.resource = resource;
    }

    /**
     * The level meaning of a word as a record carries it: the syllabus entry "长2" is the word 长; the pinyin must
     * be the official one, a neutral tone or the tone change of 一 and 不 aside (yīhuìr is yíhuìr, but 分 fèn is not
     * fēn). Without a pinyin, the level must have a single official reading of the word.
     */
    public Optional<LevelMeaningResponse> find(String hanzi, String levelCode, String pinyin) {
        if (hanzi == null || levelCode == null) return Optional.empty();
        List<LevelMeaningResponse> candidates = index().get(key(hanzi, levelCode));
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        if (pinyin == null || pinyin.isBlank()) {
            return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
        }
        return candidates.stream().filter(c -> samePinyin(c.getPinyin(), pinyin)).findFirst();
    }

    /** Whether the file has level meanings for this level yet (HSK 4–9 are generated later). */
    public boolean coversLevel(String levelCode) {
        if (levelCode == null) return false;
        String suffix = "|" + levelCode.trim();
        return index().keySet().stream().anyMatch(k -> k.endsWith(suffix));
    }

    private static String key(String hanzi, String levelCode) {
        return hanzi.trim().replaceAll("[0-9]+$", "") + "|" + levelCode.trim();
    }

    private Map<String, List<LevelMeaningResponse>> index() {
        Map<String, List<LevelMeaningResponse>> loaded = byWordAndLevel;
        if (loaded == null) {
            synchronized (this) {
                loaded = byWordAndLevel;
                if (loaded == null) {
                    loaded = load();
                    byWordAndLevel = loaded;
                }
            }
        }
        return loaded;
    }

    private Map<String, List<LevelMeaningResponse>> load() {
        Map<String, List<LevelMeaningResponse>> index = new HashMap<>();
        Map<String, List<LevelMeaningResponse>> words = new HashMap<>();
        ClassPathResource file = new ClassPathResource(resource);
        if (!file.exists()) return index;
        try (InputStream in = file.getInputStream()) {
            for (JsonNode m : new ObjectMapper().readTree(in).path("meanings")) {
                if (!m.path("official").asBoolean(false) || m.path("main").asText("").isBlank()) continue;
                List<String> secondary = new ArrayList<>();
                m.path("secondary").forEach(s -> secondary.add(s.asText()));
                List<LevelMeaningResponse.Collocation> collocations = new ArrayList<>();
                m.path("collocations").forEach(c -> collocations.add(
                        new LevelMeaningResponse.Collocation(c.path("zh").asText(), c.path("vi").asText())));
                LevelMeaningResponse meaning = LevelMeaningResponse.builder()
                        .syllabusVersion(m.path("ver").asText(null))
                        .level(m.path("l").asText())
                        .pinyin(m.path("py").asText())
                        .partOfSpeech(blankToNull(m.path("taught").asText("")))
                        .partOfSpeechVi(blankToNull(PartOfSpeechVi.translate(m.path("taught").asText(""))))
                        .main(m.path("main").asText())
                        .secondary(secondary)
                        .collocations(collocations)
                        .note(blankToNull(m.path("note").asText("")))
                        .newAtLevel(blankToNull(m.path("new").asText("")))
                        .build();
                index.computeIfAbsent(key(m.path("w").asText(), meaning.getLevel()), k -> new ArrayList<>()).add(meaning);
                words.computeIfAbsent(key(m.path("w").asText(), ""), k -> new ArrayList<>()).add(meaning);
            }
            // What each level adds to the levels below: the same word read the same way (长 zhǎng at HSK 3 is not
            // the 长 cháng of HSK 2), lowest level first.
            for (List<LevelMeaningResponse> readings : words.values()) {
                for (LevelMeaningResponse meaning : readings) {
                    List<LevelMeaningResponse> earlier = readings.stream()
                            .filter(other -> LEVELS.indexOf(other.getLevel()) < LEVELS.indexOf(meaning.getLevel()))
                            .filter(other -> samePinyin(other.getPinyin(), meaning.getPinyin()))
                            .sorted(java.util.Comparator.comparingInt(other -> LEVELS.indexOf(other.getLevel())))
                            .map(LevelMeaningService::withoutHistory)
                            .toList();
                    meaning.setEarlierLevels(earlier);
                }
            }
        } catch (IOException e) {
            log.warn("Could not read {}; words keep their dictionary meaning", resource, e);
        }
        log.info("Level meanings: {} official (word, level) pairs", index.size());
        return index;
    }

    /** A lower level as listed under a higher one: its own meaning, without the levels below it again. */
    private static LevelMeaningResponse withoutHistory(LevelMeaningResponse meaning) {
        return LevelMeaningResponse.builder().syllabusVersion(meaning.getSyllabusVersion()).level(meaning.getLevel())
                .pinyin(meaning.getPinyin()).partOfSpeech(meaning.getPartOfSpeech()).partOfSpeechVi(meaning.getPartOfSpeechVi())
                .main(meaning.getMain()).secondary(meaning.getSecondary()).collocations(meaning.getCollocations())
                .note(meaning.getNote()).newAtLevel(meaning.getNewAtLevel()).earlierLevels(List.of()).build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Same syllables with the same tones, where a missing tone mark (neutral tone) and the tone of 一 (yi) and 不 (bu)
     * may differ: the sources write 哥哥 gēge or gēgē, 一会儿 yīhuìr or yíhuìr.
     */
    static boolean samePinyin(String official, String other) {
        String a = decompose(official), b = decompose(other);
        if (a.equals(b)) return true;
        String baseA = a.replaceAll("\\p{M}", ""), baseB = b.replaceAll("\\p{M}", "");
        if (!baseA.equals(baseB)) return false;
        char[] marksA = marks(a, baseA.length()), marksB = marks(b, baseB.length());
        for (int i = 0; i < baseA.length(); i++) {
            if (marksA[i] == marksB[i] || marksA[i] == 0 || marksB[i] == 0) continue;
            boolean yi = baseA.charAt(i) == 'i' && i > 0 && baseA.charAt(i - 1) == 'y'
                    && (i + 1 == baseA.length() || !Character.isLetter(baseA.charAt(i + 1)) || "ny".indexOf(baseA.charAt(i + 1)) < 0);
            boolean bu = baseA.charAt(i) == 'u' && i > 0 && baseA.charAt(i - 1) == 'b';
            if (!yi && !bu) return false;
        }
        return true;
    }

    /** Lower case, decomposed (ǚ = u + diaeresis + caron), without spaces and apostrophes; ü kept as u + mark. */
    private static String decompose(String pinyin) {
        String s = Normalizer.normalize(pinyin.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return s.replaceAll("[\\s'’\\-]", "");
    }

    /** The tone mark on each base letter (0 for none); the diaeresis of ü is not a tone. */
    private static char[] marks(String decomposed, int length) {
        char[] marks = new char[length];
        int base = -1;
        for (char c : decomposed.toCharArray()) {
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                if (base >= 0 && c != '̈') marks[base] = c;
            } else {
                base++;
            }
        }
        return marks;
    }
}
