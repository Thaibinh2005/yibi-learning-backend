package com.yibi.backend.service.plan;

import com.yibi.backend.service.plan.PathQuiz.Question;
import com.yibi.backend.service.plan.PathQuiz.Word;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PathQuizTest {

    private static final List<Word> POOL = List.of(
            new Word("爸爸", "bàba", "bố; cha"), new Word("妈妈", "māma", "mẹ"), new Word("哥哥", "gēge", "anh trai"),
            new Word("姐姐", "jiějie", "chị gái"), new Word("弟弟", "dìdi", "em trai"), new Word("妹妹", "mèimei", "em gái"));

    @Test
    void theSameSeedGivesTheSameQuestions() {
        assertEquals(PathQuiz.forWords(POOL, POOL, 5, new Random(42)), PathQuiz.forWords(POOL, POOL, 5, new Random(42)));
    }

    @Test
    void everyQuestionHasFourDifferentOptionsIncludingTheAnswer() {
        List<Question> questions = PathQuiz.forWords(POOL, POOL, 6, new Random(1));
        assertEquals(6, questions.size());
        for (Question question : questions) {
            assertEquals(4, new HashSet<>(question.options()).size());
            assertTrue(question.options().contains(question.answer()));
        }
    }

    @Test
    void questionsRespectTheLimitAndHaveUniqueIds() {
        List<Question> questions = PathQuiz.forWords(POOL, POOL, 3, new Random(1));
        assertEquals(3, questions.size());
        assertEquals(3, questions.stream().map(Question::id).distinct().count());
    }

    @Test
    void aWordWithoutMeaningIsAskedByPronunciation() {
        Word word = new Word("的", "de", "");
        Question question = PathQuiz.forWords(List.of(word), POOL, 1, new Random(1)).get(0);
        assertEquals("HANZI_TO_PINYIN", question.type());
        assertEquals("de", question.answer());
    }

    @Test
    void aCharacterQuestionNeverOffersTheOtherLettersOfTheWord() {
        Map<String, Object> item = Map.of("hanzi", "老", "word", "老师", "pinyin", "lǎoshī", "meaning_vi", "giáo viên");
        Question question = PathQuiz.forCharacters(List.of(item), List.of("师", "爸", "妈", "哥", "姐"), 1, new Random(3)).get(0);
        assertEquals("老", question.answer());
        assertFalse(question.options().contains("师"));
        assertTrue(question.prompt().contains("＿师"));
    }

    @Test
    void preparedQuestionsWithAWrongAnswerKeyAreDropped() {
        Map<String, Object> good = Map.of("question", "Q1", "options", List.of("a", "b", "c", "d"), "answer", "b", "explanation", "");
        Map<String, Object> broken = Map.of("question", "Q2", "options", List.of("a", "b"), "answer", "z", "explanation", "");
        List<Question> questions = PathQuiz.forPrepared(List.of(good, broken), "reading", 5, new Random(1));
        assertEquals(1, questions.size());
        assertEquals("reading", questions.get(0).skill());
    }

    @Test
    void mixGivesUniqueIdsAfterJoiningSets() {
        List<Question> joined = new java.util.ArrayList<>(PathQuiz.forWords(POOL, POOL, 3, new Random(1)));
        joined.addAll(PathQuiz.forWords(POOL, POOL, 3, new Random(2)));
        assertEquals(4, PathQuiz.mix(joined, 4, new Random(1)).stream().map(Question::id).distinct().count());
    }

    @Test
    void aNodeWithoutQuestionsCountsAsPassed() {
        assertEquals(100, PathQuiz.scorePercent(0, 0));
        assertEquals(75, PathQuiz.scorePercent(6, 8));
    }

    @Test
    void dictionaryCrossReferencesNeverReachTheLearner() {
        assertEquals("nhà", PathQuiz.shorten("dùng trong 傢伙|家伙[jia1 huo5]; nhà"));
        assertEquals("", PathQuiz.shorten("dùng trong 傢伙|家伙[jia1 huo5]"));
        Question question = PathQuiz.forWords(List.of(new Word("家", "jiā", "dùng trong 傢伙|家伙[jia1 huo5]")), POOL, 1, new Random(1)).get(0);
        assertEquals("HANZI_TO_PINYIN", question.type());
    }

    @Test
    void longDictionaryEntriesAreShortenedForButtons() {
        String shortened = PathQuiz.shorten("yêu; thích; tình cảm; có khuynh hướng (làm gì đó); có xu hướng (xảy ra)");
        assertTrue(shortened.length() <= 60);
        assertTrue(shortened.startsWith("yêu; thích"));
    }
}
