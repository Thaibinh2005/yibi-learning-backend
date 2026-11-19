package com.yibi.backend.service.plan;

import com.yibi.backend.service.plan.LessonPlan.Plan;
import com.yibi.backend.service.plan.LessonPlan.Step;
import com.yibi.backend.service.plan.PathQuiz.Question;
import com.yibi.backend.service.plan.PathQuiz.Word;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The amount of each word taught and asked grows with the HSK level. */
class KnowledgeDepthTest {

    private static final String DUI = "đúng; chính xác; hướng đến; tại; về; liên quan đến";

    private static final List<Word> POOL = List.of(
            new Word("对", "duì", DUI, "他对吗？", "Anh ấy đúng không?"),
            new Word("好", "hǎo", "tốt; hay; khỏe", "你好！", "Xin chào!"),
            new Word("大", "dà", "to; lớn; già"), new Word("小", "xiǎo", "nhỏ; bé; trẻ"),
            new Word("多", "duō", "nhiều; hơn"), new Word("少", "shǎo", "ít; thiếu"),
            new Word("爸爸", "bàba", "bố; cha"), new Word("妈妈", "māma", "mẹ"), new Word("哥哥", "gēge", "anh trai"),
            new Word("姐姐", "jiějie", "chị gái"), new Word("老师", "lǎoshī", "giáo viên; thầy cô"));

    private static final Map<String, Object> DUI_ITEM = Map.of("simplified", "对", "pinyin", "duì", "meaning_vi", DUI,
            "example", Map.of("zh", "他对吗？", "en", "Is he right?"));

    @Test
    void levelsMapToTheFourBands() {
        assertEquals(KnowledgeDepth.CORE, KnowledgeDepth.of(1));
        assertEquals(KnowledgeDepth.EXPAND, KnowledgeDepth.of(2));
        assertEquals(KnowledgeDepth.EXPAND, KnowledgeDepth.of(3));
        assertEquals(KnowledgeDepth.NUANCE, KnowledgeDepth.of(4));
        assertEquals(KnowledgeDepth.NUANCE, KnowledgeDepth.of(6));
        assertEquals(KnowledgeDepth.DEEP, KnowledgeDepth.of(7));
        assertEquals(KnowledgeDepth.DEEP, KnowledgeDepth.of(9));
    }

    @Test
    void theCardShowsMoreMeaningsAsTheLevelRises() {
        assertEquals("đúng", KnowledgeDepth.CORE.cardMeaning(DUI));
        assertEquals("đúng; chính xác", KnowledgeDepth.EXPAND.cardMeaning(DUI));
        assertEquals("đúng; chính xác; hướng đến", KnowledgeDepth.NUANCE.cardMeaning(DUI));
        assertEquals(DUI, KnowledgeDepth.DEEP.cardMeaning(DUI));
    }

    @Test
    void crossReferencesAreNeverShown() {
        assertEquals("cái này", KnowledgeDepth.DEEP.cardMeaning("dùng trong 傢伙[jia1 huo5]; cái này"));
    }

    @Test
    void hsk1AsksOnlyTheMainMeaning() {
        Question q = PathQuiz.ask(POOL.get(0), POOL, PathQuiz.HANZI_TO_MEANING, KnowledgeDepth.CORE, new Random(1));
        assertEquals("đúng", q.answer());
        // The wrong options are single meanings too, so the answer does not stand out by length.
        q.options().forEach(option -> assertFalse(option.contains(";"), option));
    }

    @Test
    void hsk1LessonCardsKeepOneMeaningAndDropLongExamples() {
        Map<String, Object> longExample = Map.of("simplified", "好", "pinyin", "hǎo", "meaning_vi", "tốt; hay",
                "example", Map.of("zh", "我觉得这个办法非常好，我们明天就开始吧。"));
        Plan plan = LessonPlan.build(Map.of("words", List.of(DUI_ITEM, longExample)), POOL, List.of(), KnowledgeDepth.mark(8), 1);
        List<Map<String, Object>> cards = plan.steps().stream().filter(s -> LessonPlan.TEACH_WORD.equals(s.kind())).map(Step::data).toList();
        Map<String, Object> dui = cards.stream().filter(c -> "对".equals(c.get("simplified"))).findFirst().orElseThrow();
        Map<String, Object> hao = cards.stream().filter(c -> "好".equals(c.get("simplified"))).findFirst().orElseThrow();
        assertEquals("đúng", dui.get("meaning_vi"));
        assertNotNull(dui.get("example"), "a short example stays");
        assertNull(hao.get("example"), "a long example is left for later levels");
    }

    @Test
    void newSeedsAskTheMeaningsOfTheLevelAndOldOnesKeepTheDictionary() {
        assertTrue(KnowledgeDepth.usesLevelMeanings(KnowledgeDepth.mark(8)));
        assertFalse(KnowledgeDepth.usesLevelMeanings(8 | KnowledgeDepth.LEVEL_RULES), "a quiz started before is graded as it was");
        // Still exact as a browser number (53 bits).
        assertTrue(KnowledgeDepth.mark(Integer.MAX_VALUE) < (1L << 53));
    }

    @Test
    void aLevelMeaningIsShownWholeOnTheLessonCard() {
        // Main and secondary meanings of HSK 2 点 from the syllabus: not cut down to the dictionary's first senses.
        Map<String, Object> dian = Map.of("simplified", "点", "pinyin", "diǎn", "meaning_vi", "gọi món; chấm, điểm (đánh dấu)",
                LessonPlan.LEVEL_MEANING, true);
        Plan plan = LessonPlan.build(Map.of("words", List.of(dian)), POOL, List.of(), KnowledgeDepth.mark(8), 1);
        Map<String, Object> card = plan.steps().stream().filter(s -> LessonPlan.TEACH_WORD.equals(s.kind())).map(Step::data)
                .findFirst().orElseThrow();
        assertEquals("gọi món; chấm, điểm (đánh dấu)", card.get("meaning_vi"));
    }

    @Test
    void hsk1NeverAsksTheNewKinds() {
        for (long seed = 0; seed < 20; seed++) {
            Plan plan = LessonPlan.build(Map.of("words", List.of(DUI_ITEM)), POOL, List.of(), KnowledgeDepth.mark(seed), 1);
            plan.questions().forEach(q -> assertFalse(
                    q.type().equals(PathQuiz.WORD_IN_SENTENCE) || q.type().equals(PathQuiz.NOT_A_MEANING), q.type()));
        }
    }

    @Test
    void aWordIsFilledIntoItsOwnExampleSentence() {
        Question q = PathQuiz.ask(POOL.get(0), POOL, PathQuiz.WORD_IN_SENTENCE, KnowledgeDepth.EXPAND, new Random(2));
        assertEquals(PathQuiz.WORD_IN_SENTENCE, q.type());
        assertEquals("对", q.answer());
        assertTrue(q.prompt().contains("他＿＿吗？"), q.prompt());
        assertEquals(4, new HashSet<>(q.options()).size());
        // Same length as the answer, and none already in the sentence.
        q.options().forEach(option -> assertEquals(1, option.length(), option));
        assertFalse(q.options().contains("他"));
        assertTrue(q.explanation().contains("他对吗？"));
    }

    @Test
    void aWordWithoutAnExampleIsStillAskedInDepthWhenItHasSeveralMeanings() {
        Question q = PathQuiz.ask(new Word("大", "dà", "to; lớn; già"), POOL, PathQuiz.WORD_IN_SENTENCE, KnowledgeDepth.NUANCE, new Random(3));
        assertEquals(PathQuiz.NOT_A_MEANING, q.type());
    }

    @Test
    void aWordWithNeitherFallsBackToItsMeaning() {
        Question q = PathQuiz.ask(new Word("妈妈", "māma", "mẹ"), POOL, PathQuiz.WORD_IN_SENTENCE, KnowledgeDepth.NUANCE, new Random(3));
        assertEquals(PathQuiz.HANZI_TO_MEANING, q.type());
    }

    @Test
    void sentenceOptionsShareTheWordClassWhenThePoolAllows() {
        Word target = new Word("不见得", "bújiànde", "chưa chắc", "他说的不见得对。", null, "副");
        List<Word> pool = List.of(target,
                new Word("不至于", "búzhìyú", "không đến nỗi", null, null, "副"),
                new Word("未必", "wèibì", "chưa chắc", null, null, "副"),
                new Word("毕竟", "bìjìng", "rốt cuộc", null, null, "副"),
                new Word("啦啦队", "lālāduì", "đội cổ vũ", null, null, "名"),
                new Word("开发区", "kāifāqū", "khu khai thác", null, null, "名"),
                new Word("创始人", "chuàngshǐrén", "người sáng lập", null, null, "名"));
        for (long seed = 0; seed < 10; seed++) {
            Question q = PathQuiz.ask(target, pool, PathQuiz.WORD_IN_SENTENCE, KnowledgeDepth.DEEP, new Random(seed));
            assertEquals(PathQuiz.WORD_IN_SENTENCE, q.type());
            assertTrue(q.options().containsAll(List.of("不见得", "不至于", "未必", "毕竟")), q.options().toString());
        }
    }

    @Test
    void theMainWordClassIsTheFirstListed() {
        assertEquals("形", new Word("对", "duì", "", null, null, "形、介、（动、量）").mainClass());
        assertEquals("", new Word("对", "duì", "").mainClass());
    }

    @Test
    void notAMeaningOffersThreeRealMeaningsAndOneFromAnotherWord() {
        for (long seed = 0; seed < 30; seed++) {
            Question q = PathQuiz.ask(POOL.get(0), POOL, PathQuiz.NOT_A_MEANING, KnowledgeDepth.NUANCE, new Random(seed));
            assertEquals(PathQuiz.NOT_A_MEANING, q.type());
            List<String> own = KnowledgeDepth.senses(DUI);
            assertFalse(own.contains(q.answer()), q.answer());
            assertEquals(4, new HashSet<>(q.options()).size());
            assertEquals(3, q.options().stream().filter(own::contains).count());
        }
    }

    @Test
    void notAMeaningNeedsAtLeastThreeMeanings() {
        Question q = PathQuiz.ask(new Word("妈妈", "māma", "mẹ"), POOL, PathQuiz.NOT_A_MEANING, KnowledgeDepth.DEEP, new Random(1));
        assertNotEquals(PathQuiz.NOT_A_MEANING, q.type());
    }

    @Test
    void higherLevelsAskInContextMoreThanByTranslation() {
        assertTrue(KnowledgeDepth.CORE.kinds().stream().noneMatch(PathQuiz.WORD_IN_SENTENCE::equals));
        assertTrue(KnowledgeDepth.EXPAND.kinds().contains(PathQuiz.WORD_IN_SENTENCE));
        assertTrue(KnowledgeDepth.NUANCE.kinds().contains(PathQuiz.NOT_A_MEANING));
        long deepContext = KnowledgeDepth.DEEP.kinds().stream()
                .filter(k -> k.equals(PathQuiz.WORD_IN_SENTENCE) || k.equals(PathQuiz.NOT_A_MEANING)).count();
        assertTrue(deepContext > KnowledgeDepth.DEEP.kinds().size() / 2);
    }

    @Test
    void theSameSeedAndLevelGiveTheSameLessonSoItCanBeGraded() {
        Map<String, Object> content = Map.of("words", List.of(DUI_ITEM));
        for (int level = 1; level <= 7; level++) {
            long seed = KnowledgeDepth.mark(5);
            assertEquals(LessonPlan.build(content, POOL, List.of(), seed, level).questions(),
                    LessonPlan.build(content, POOL, List.of(), seed, level).questions());
        }
    }

    @Test
    void aSeedFromBeforeTheseRulesIsRebuiltTheOldWay() {
        // Seeds handed out before depth by level were at most Integer.MAX_VALUE and carry no mark.
        assertEquals(KnowledgeDepth.LEGACY, KnowledgeDepth.forSeed(7, Integer.MAX_VALUE));
        assertEquals(KnowledgeDepth.DEEP, KnowledgeDepth.forSeed(7, KnowledgeDepth.mark(Integer.MAX_VALUE)));
        Plan old = LessonPlan.build(Map.of("words", List.of(DUI_ITEM)), POOL, List.of(), 8, 1);
        Map<String, Object> card = old.steps().stream().filter(s -> LessonPlan.TEACH_WORD.equals(s.kind())).findFirst().orElseThrow().data();
        assertEquals(DUI, card.get("meaning_vi"), "an old lesson keeps showing every meaning");
        old.questions().forEach(q -> assertTrue(List.of(PathQuiz.MEANING_TO_HANZI, PathQuiz.HANZI_TO_MEANING, PathQuiz.HANZI_TO_PINYIN).contains(q.type())));
    }

    @Test
    void aMarkedSeedStaysExactInTheBrowser() {
        // JavaScript numbers hold integers exactly up to 2^53 - 1.
        assertTrue(KnowledgeDepth.mark(Integer.MAX_VALUE) < (1L << 53));
        assertTrue(KnowledgeDepth.mark(LessonPlan.seed("HSK", List.of("speaking", "phonetics"), Integer.MAX_VALUE)) < (1L << 53));
    }
}
