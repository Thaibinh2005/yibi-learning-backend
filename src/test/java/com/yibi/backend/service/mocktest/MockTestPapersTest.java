package com.yibi.backend.service.mocktest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yibi.backend.exception.BadRequestException;
import com.yibi.backend.service.mocktest.MockTestPapers.Item;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockTestPapersTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode hsk1() {
        return MockTestCatalog.readBundles(json).get("hsk1-1110");
    }

    /** A small paper with a listening group, a fill-in item and open writing. */
    private ObjectNode sample() throws IOException {
        return (ObjectNode) json.readTree("""
                {"id": "t", "title": "HSK 3", "level": 3, "parts": [{"skill": "listening", "minutes": 20}],
                 "problems": ["q01: kiểm tra"],
                 "sections": [
                  {"skill": "listening", "part": 3, "layout": "text_choice", "range": [1, 2],
                   "introAudio": {"file": "audio/intro.mp3", "start": 1, "end": 9},
                   "groups": [{"id": "a1", "range": [1, 2], "transcript": "一段话",
                               "audio": {"file": "audio/g.mp3", "start": 10, "end": 70, "needsReview": true}}],
                   "questions": [
                    {"no": 1, "group": "a1", "answer": "B", "options": [{"key": "A"}, {"key": "B"}, {"key": "C"}]},
                    {"no": 2, "group": "a1", "answer": "D", "options": [{"key": "A"}, {"key": "B"}, {"key": "C"}]}]},
                  {"skill": "writing", "part": 1, "layout": "fill_char", "range": [3, 4],
                   "questions": [
                    {"no": 3, "type": "fill", "question": "北（　）", "blankPinyin": "fāng", "answer": "方"},
                    {"no": 4, "type": "write", "word": "开心", "image": "img/q04.png", "reference": "他很开心。"}]}]}
                """);
    }

    @Test
    void bundledHsk1PaperIsCompleteAndAnswered() {
        JsonNode paper = hsk1();
        assertThat(paper.path("questionCount").asInt()).isEqualTo(40);
        Map<Integer, Item> key = MockTestPapers.answerKey(paper);
        assertThat(key).hasSize(40);
        assertThat(key.values()).allSatisfy(item -> assertThat(item.answer()).matches("[A-F]"));
        assertThat(MockTestPapers.reviewProblems(paper)).isEmpty();
        assertThat(MockTestPapers.clips(paper)).containsKeys("L1_intro", "q01", "q20").hasSize(24);
    }

    @Test
    void timedExamHidesAnswersTranscriptsAndModelAnswersUntilHandedIn() throws IOException {
        JsonNode paper = hsk1();
        JsonNode exam = MockTestPapers.visiblePaper(paper, "EXAM", false);
        JsonNode first = exam.path("sections").get(0).path("questions").get(0);
        assertThat(first.has("answer")).isFalse();
        assertThat(first.has("transcript")).isFalse();
        // The example keeps its printed answer, and the stored paper is not changed.
        assertThat(exam.path("sections").get(0).path("example").path("answer").asText()).isEqualTo("C");
        assertThat(paper.path("sections").get(0).path("questions").get(0).path("answer").asText()).isEqualTo("C");
        assertThat(MockTestPapers.visiblePaper(paper, "PRACTICE", false)).isSameAs(paper);
        assertThat(MockTestPapers.visiblePaper(paper, "EXAM", true)).isSameAs(paper);

        JsonNode grouped = MockTestPapers.visiblePaper(sample(), "EXAM", false);
        assertThat(grouped.path("sections").get(0).path("groups").get(0).has("transcript")).isFalse();
        assertThat(grouped.path("sections").get(1).path("questions").get(1).has("reference")).isFalse();
    }

    @Test
    void answersAreKeptAndScoredByType() {
        Item choice = new Item("reading", "choice", "C");
        Item fill = new Item("writing", "fill", "的");
        Item write = new Item("writing", "write", null);
        assertThat(MockTestPapers.keepAnswer(choice, "C")).isEqualTo("C");
        assertThat(MockTestPapers.keepAnswer(choice, "的")).isNull();
        assertThat(MockTestPapers.keepAnswer(fill, " 的 ")).isEqualTo("的");
        assertThat(MockTestPapers.keepAnswer(fill, "太长太长太长太长太长")).isNull();
        assertThat(MockTestPapers.keepAnswer(write, "  我很高兴。 ")).isEqualTo("我很高兴。");
        assertThat(MockTestPapers.keepAnswer(write, "字".repeat(5000))).hasSize(3000);

        assertThat(MockTestPapers.correct(choice, "C")).isTrue();
        assertThat(MockTestPapers.correct(fill, "的。")).isTrue();
        assertThat(MockTestPapers.correct(fill, "得")).isFalse();
        assertThat(write.graded()).isFalse();
        assertThat(MockTestPapers.correct(write, "我很高兴。")).isFalse();
    }

    @Test
    void reviewProblemsBlockPublishingUntilSettled() throws IOException {
        ObjectNode paper = sample();
        assertThat(MockTestPapers.reviewProblems(paper)).containsExactly(
                "Câu 1–2: cần nghe lại đoạn audio nhóm", "Câu 2: đáp án D không có trong các lựa chọn");

        ((ObjectNode) paper.path("sections").get(0).path("groups").get(0).path("audio")).put("needsReview", false);
        ((ObjectNode) paper.path("sections").get(0).path("questions").get(1)).put("answer", "A");
        ((ObjectNode) paper.path("sections").get(1).path("questions").get(0)).remove("answer");
        assertThat(MockTestPapers.reviewProblems(paper)).containsExactly("Câu 3: thiếu đáp án");
    }

    @Test
    void learnersGetThePaperWithoutReviewFlagsOrNotes() throws IOException {
        JsonNode clean = MockTestPapers.forLearners(sample());
        assertThat(clean.has("problems")).isFalse();
        assertThat(clean.path("sections").get(0).path("groups").get(0).path("audio").has("needsReview")).isFalse();
        assertThat(clean.path("sections").get(0).path("groups").get(0).path("audio").path("file").asText()).isEqualTo("audio/g.mp3");
    }

    @Test
    void clipsAreFoundByKeyAndMediaPathsRewritten() throws IOException {
        ObjectNode paper = sample();
        assertThat(MockTestPapers.clips(paper)).containsOnlyKeys("L3_intro", "q01-02");
        MockTestPapers.rewriteMedia(paper, path -> "/api/mock-tests/media/" + path);
        assertThat(paper.path("sections").get(0).path("introAudio").path("file").asText()).isEqualTo("/api/mock-tests/media/audio/intro.mp3");
        assertThat(paper.path("sections").get(1).path("questions").get(1).path("image").asText()).isEqualTo("/api/mock-tests/media/img/q04.png");
    }

    @Test
    void editedDraftsMustKeepTheirStructure() throws IOException {
        ObjectNode paper = sample();
        MockTestPapers.validateStructure(paper);
        ((ObjectNode) paper.path("sections").get(1).path("questions").get(0)).put("no", 1);
        assertThatThrownBy(() -> MockTestPapers.validateStructure(paper)).isInstanceOf(BadRequestException.class);

        ObjectNode other = sample();
        ((ObjectNode) other.path("sections").get(0).path("questions").get(0)).put("answer", "B, C");
        assertThatThrownBy(() -> MockTestPapers.validateStructure(other)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void countsPerSkillFollowTheSectionsAndKeepTheMinutes() throws IOException {
        ObjectNode paper = sample();
        MockTestAdminService.refreshCounts(paper);
        assertThat(paper.path("questionCount").asInt()).isEqualTo(4);
        assertThat(paper.path("parts").get(0).path("minutes").asInt()).isEqualTo(20);
        assertThat(paper.path("parts").get(1).path("skill").asText()).isEqualTo("writing");
        assertThat(paper.path("parts").get(1).path("questionCount").asInt()).isEqualTo(2);
    }

    @Test
    void unzipKeepsOnlyExpectedNamesInsideTheFolder() throws IOException {
        Path dir = Files.createTempDirectory("unzip-test");
        Path zip = dir.resolve("r.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : new String[]{"exam.json", "img/q01.png", "../evil.txt", "audio/../../x.mp3", "other.bin"}) {
                out.putNextEntry(new ZipEntry(name));
                out.write("x".getBytes());
                out.closeEntry();
            }
        }
        Path target = dir.resolve("out");
        MockTestAdminService.unzip(zip, target, MockTestAdminService.RESULT_ENTRY);
        assertThat(target.resolve("exam.json")).exists();
        assertThat(target.resolve("img/q01.png")).exists();
        assertThat(dir.resolve("evil.txt")).doesNotExist();
        assertThat(target.resolve("other.bin")).doesNotExist();
        assertThat(Pattern.matches(MockTestAdminService.RESULT_ENTRY.pattern(), "audio/../../x.mp3")).isFalse();
    }
}
