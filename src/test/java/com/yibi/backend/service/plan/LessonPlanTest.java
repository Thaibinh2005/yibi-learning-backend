package com.yibi.backend.service.plan;

import com.yibi.backend.service.plan.LessonPlan.Plan;
import com.yibi.backend.service.plan.LessonPlan.Step;
import com.yibi.backend.service.plan.PathQuiz.Question;
import com.yibi.backend.service.plan.PathQuiz.Word;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LessonPlanTest {

    private static final List<Map<String, Object>> WORDS = List.of(
            word("爸爸", "bàba", "bố"), word("妈妈", "māma", "mẹ"), word("哥哥", "gēge", "anh trai"),
            word("姐姐", "jiějie", "chị gái"), word("弟弟", "dìdi", "em trai"));

    private static final List<Word> POOL = List.of(
            new Word("爸爸", "bàba", "bố"), new Word("妈妈", "māma", "mẹ"), new Word("哥哥", "gēge", "anh trai"),
            new Word("姐姐", "jiějie", "chị gái"), new Word("弟弟", "dìdi", "em trai"), new Word("妹妹", "mèimei", "em gái"));

    private static final List<String> CHARACTERS = List.of("爸", "妈", "哥", "姐", "弟", "妹", "好", "你");

    private static final Map<String, Object> CONTENT = Map.of(
            "words", WORDS,
            "characters", List.of(character("爸", "爸爸"), character("妈", "妈妈"), character("哥", "哥哥"), character("姐", "姐姐")),
            "points", List.of(Map.of("content", "是", "explanation_vi", "là")),
            "questions", List.of(Map.of("question", "Chọn từ đúng", "options", List.of("是", "的", "了", "吗"), "answer", "是", "explanation", "")),
            "lines", List.of(Map.of("speaker", "A", "zh", "这是我爸爸。", "pinyin", "", "vi", ""), Map.of("speaker", "B", "zh", "你好！", "pinyin", "", "vi", "")));

    private static Map<String, Object> word(String hanzi, String pinyin, String meaning) {
        return Map.of("simplified", hanzi, "pinyin", pinyin, "meaning_vi", meaning);
    }

    private static Map<String, Object> character(String hanzi, String word) {
        return Map.of("hanzi", hanzi, "word", word, "pinyin", "", "meaning_vi", "x");
    }

    private static Plan plan(long seed) {
        return LessonPlan.build(CONTENT, POOL, CHARACTERS, seed);
    }

    private static List<String> kinds(Plan plan) {
        return plan.steps().stream().map(Step::kind).toList();
    }

    @Test
    void theSameSeedGivesTheSameLessonSoItCanBeGradedAgain() {
        assertEquals(plan(99), plan(99));
    }

    @Test
    void differentSeedsWeaveTheLessonDifferently() {
        Set<List<String>> shapes = new HashSet<>();
        for (long seed = 0; seed < 40; seed += 8) shapes.add(kinds(plan(seed)));
        assertTrue(shapes.size() > 1, "shapes: " + shapes.size());
    }

    @Test
    void everyWordIsTaughtExactlyOnce() {
        List<Object> taught = plan(8).steps().stream().filter(s -> LessonPlan.TEACH_WORD.equals(s.kind())).map(s -> s.data().get("simplified")).toList();
        assertEquals(5, taught.size());
        assertEquals(5, new HashSet<>(taught).size());
    }

    @Test
    void noWordIsAskedBeforeItIsTaught() {
        for (long seed = 0; seed < 300; seed++) {
            Plan plan = plan(seed);
            Set<String> taught = new HashSet<>();
            for (Step step : plan.steps()) {
                if (LessonPlan.TEACH_WORD.equals(step.kind())) taught.add(String.valueOf(step.data().get("simplified")));
                if (!LessonPlan.QUESTION.equals(step.kind())) continue;
                Question question = plan.questions().stream().filter(q -> q.id().equals(step.questionId())).findFirst().orElseThrow();
                if ("grammar".equals(question.skill())) continue;
                List<String> mentioned = WORDS.stream().map(w -> String.valueOf(w.get("simplified")))
                        .filter(w -> question.explanation().contains(w)).toList();
                assertFalse(mentioned.isEmpty(), question.toString());
                assertTrue(taught.containsAll(mentioned), "seed " + seed + ": " + question.prompt());
            }
        }
    }

    @Test
    void aGrammarQuestionFollowsItsNote() {
        for (long seed = 0; seed < 100; seed++) {
            List<String> kinds = kinds(plan(seed));
            int note = kinds.indexOf(LessonPlan.TEACH_GRAMMAR);
            assertEquals(LessonPlan.QUESTION, kinds.get(note + 1));
        }
    }

    @Test
    void aLessonIsWovenFromEverySkill() {
        Plan plan = plan(LessonPlan.seed("HSK", List.of(), 12345));
        Set<String> skills = new HashSet<>(plan.questions().stream().map(Question::skill).toList());
        assertEquals(Set.of("vocabulary", "phonetics", "characters", "grammar"), skills);
        assertTrue(kinds(plan).contains(LessonPlan.SPEAK));
    }

    @Test
    void speakingNeverComesBeforeTheWordsOfItsLine() {
        for (long seed = 0; seed < 200; seed++) {
            List<Step> steps = plan(seed).steps();
            int speak = -1;
            int taught = -1;
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (LessonPlan.SPEAK.equals(step.kind()) && step.lineIndex() == 0) speak = i;
                if (LessonPlan.TEACH_WORD.equals(step.kind()) && "爸爸".equals(step.data().get("simplified"))) taught = i;
            }
            assertTrue(taught < speak, "seed " + seed);
        }
    }

    private static long count(Plan plan, String kindOrSkill) {
        long steps = plan.steps().stream().filter(s -> kindOrSkill.equals(s.kind())).count();
        return steps + plan.questions().stream().filter(q -> kindOrSkill.equals(q.skill())).count();
    }

    @Test
    void theGoalChangesWhatALessonStresses() {
        Plan exam = plan(LessonPlan.seed("HSK", List.of(), 800));
        Plan talk = plan(LessonPlan.seed("COMMUNICATION", List.of(), 800));
        assertTrue(count(exam, "characters") > count(talk, "characters"));
        assertTrue(count(talk, LessonPlan.SPEAK) > count(exam, LessonPlan.SPEAK));
    }

    @Test
    void weakSkillsGetMorePractice() {
        Plan plain = plan(LessonPlan.seed("COMMUNICATION", List.of(), 800));
        assertTrue(count(plan(LessonPlan.seed("COMMUNICATION", List.of("characters"), 800)), "characters") > count(plain, "characters"));
        assertTrue(count(plan(LessonPlan.seed("COMMUNICATION", List.of("phonetics"), 800)), "phonetics") > count(plain, "phonetics"));
    }

    @Test
    void theSeedStaysExactInTheBrowser() {
        assertTrue(LessonPlan.seed("HSK", List.of("speaking", "phonetics"), Integer.MAX_VALUE) <= Integer.MAX_VALUE);
    }

    @Test
    void aLessonWithoutWordsOrSpeakingStillWorks() {
        Plan plan = LessonPlan.build(Map.of("points", CONTENT.get("points"), "questions", CONTENT.get("questions")), POOL, CHARACTERS, 3);
        assertEquals(List.of(LessonPlan.TEACH_GRAMMAR, LessonPlan.QUESTION), kinds(plan));
        assertTrue(LessonPlan.build(Map.of(), POOL, CHARACTERS, 3).steps().isEmpty());
    }

    @Test
    void questionIdsAreUniqueAndEveryQuestionHasAStep() {
        Plan plan = plan(16);
        List<String> ids = new ArrayList<>(plan.questions().stream().map(Question::id).toList());
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertEquals(ids, plan.steps().stream().filter(s -> LessonPlan.QUESTION.equals(s.kind())).map(Step::questionId).toList());
    }
}
