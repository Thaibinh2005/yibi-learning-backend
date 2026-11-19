package com.yibi.backend.dto.vocabulary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** What the word detail modal shows beyond {@link VocabularyResponse}; every list is empty when the data is missing. */
public final class WordDetailsDtos {

    private WordDetailsDtos() {}

    /**
     * Content of a word from the HSK bank: its stored example sentences (the one written for the meaning of its level
     * first), the words of the bank sharing a character with it, its characters as words of their own, and its topics.
     */
    public record WordDetailsResponse(
            Long vocabularyId,
            List<Example> examples,
            List<CharacterInfo> characters,
            List<RelatedWord> relatedWords,
            List<Topic> topics) {}

    /** forLevelMeaning: written for the meaning the word's level teaches. */
    public record Example(String zh, String pinyin, String vi, String audioUrl, String levelCode, boolean forLevelMeaning) {}

    /** A character of the word; the reading and meaning are those of the character as an HSK word, null if it is none. */
    public record CharacterInfo(String hanzi, String traditional, Long vocabularyId, String pinyin, String meaning, String levelCode) {}

    /** A word of the HSK bank containing one of this word's characters (via). */
    public record RelatedWord(Long vocabularyId, String hanzi, String pinyin, String meaning, String levelCode, String via) {}

    public record Topic(String slug, String titleVi, String icon) {}

    /**
     * The learner's state of a word: their flashcard for it (matched by the word, as the flashcards do; null if none)
     * and every deck of theirs, marking those holding the word at this level.
     */
    public record WordStudyResponse(Long vocabularyId, Card card, List<DeckMembership> decks) {}

    /** status: NEW (never reviewed), LEARNING or MASTERED (right three times in a row). */
    public record Card(String status, int repetitions, int intervalDays, BigDecimal easeFactor, LocalDate dueDate,
                       LocalDateTime lastReviewed, boolean due) {}

    public record DeckMembership(Long id, String name, boolean contains) {}
}
