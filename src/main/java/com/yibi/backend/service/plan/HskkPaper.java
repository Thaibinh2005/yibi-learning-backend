package com.yibi.backend.service.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Lays out an HSKK practice paper after the official structure: three levels, 100 points, a pass from 60.
 * Primary has 15 repeat, 10 short reply and 2 open questions; intermediate 10 repeat, 2 described situations (the real
 * test shows pictures) and 2 open questions; advanced 3 retellings, 1 passage read aloud and 2 open questions.
 * Scoring is an estimate from speech recognition; it cannot judge content, fluency or tone as a human examiner does.
 */
public final class HskkPaper {

    public static final String PRIMARY = "PRIMARY";
    public static final String INTERMEDIATE = "INTERMEDIATE";
    public static final String ADVANCED = "ADVANCED";
    public static final int PASS_PERCENT = 60;

    public static final String REPEAT = "REPEAT";
    public static final String REPLY = "REPLY";
    public static final String SPEAK = "SPEAK";
    public static final String RETELL = "RETELL";
    public static final String READ = "READ";

    /** @param listenText played once, not shown; @param showText shown on screen; @param reference what the answer is compared with */
    public record Question(String id, String kind, String listenText, String showText, String reference, int expectedChars, int prepSeconds, int answerSeconds) { }

    public record Part(String title, String instruction, int weight, List<Question> questions) { }

    /** What the database has for the paper: spoken lines and passages at or below the learner's level. */
    public record Material(List<String> lines, List<String> passages) { }

    private static final List<String> PRIMARY_REPLIES = List.of(
            "你叫什么名字？", "你今年多大？", "你是哪国人？", "你家有几口人？", "今天星期几？", "现在几点？",
            "你喜欢喝茶吗？", "你今天吃什么？", "你在哪儿学习汉语？", "你有哥哥吗？", "你喜欢什么水果？", "明天你去学校吗？",
            "今天天气怎么样？", "你会说汉语吗？", "你的生日是几月几号？", "你每天几点睡觉？", "你喜欢看电影吗？", "你家在哪儿？",
            "这个星期你忙吗？", "你朋友叫什么名字？");
    private static final List<String> PRIMARY_OPEN = List.of(
            "请介绍一下你的家人。", "你每天早上做什么？", "你喜欢吃什么？为什么？", "请说说你的好朋友。",
            "周末你常常做什么？", "请介绍一下你的学校。", "你喜欢什么运动？", "你为什么学习汉语？");
    private static final List<String> INTERMEDIATE_SITUATIONS = List.of(
            "你在饭店吃饭，发现菜不是你点的。请说说你会怎么跟服务员说。",
            "你的朋友第一次来你的城市。请介绍几个值得去的地方。",
            "你买的衣服大小不合适，想去商店换一件。请说说你会怎么做。",
            "你生病了，不能去上班或上课。请给老师或经理打个电话说明情况。",
            "朋友想学做一个菜。请一步一步告诉他怎么做。",
            "你的手机丢了。请说说当时发生了什么，你打算怎么办。");
    private static final List<String> INTERMEDIATE_OPEN = List.of(
            "你觉得住在大城市好还是小城市好？为什么？", "你最难忘的一次旅行是什么？请说一说。",
            "学习外语最好的方法是什么？", "你觉得运动对生活有什么好处？", "你以后想做什么工作？为什么？",
            "你喜欢一个人旅行还是跟朋友一起旅行？为什么？", "上网对学习有什么好处和坏处？", "请谈谈对你影响最大的一个人。");
    private static final List<String> ADVANCED_OPEN = List.of(
            "有人认为成功主要靠努力，也有人认为机会更重要。你怎么看？",
            "科技的发展让人们的关系更近了还是更远了？请谈谈你的看法。",
            "你认为应该怎样处理工作和生活之间的关系？",
            "保护环境是政府的责任还是每个人的责任？为什么？",
            "你认为孩子应该从小学习外语吗？请说明理由。",
            "面对压力时，你认为最有效的办法是什么？",
            "传统文化在现代社会还有什么意义？",
            "网上购物对人们的生活带来了哪些改变？");

    private HskkPaper() { }

    public static int requiredLevel(String tier) {
        return switch (tier) {
            case PRIMARY -> 1;
            case INTERMEDIATE -> 3;
            case ADVANCED -> 5;
            default -> throw new IllegalArgumentException("Unknown tier");
        };
    }

    /** Content comes from the tier's own levels, and never from above where the learner is. */
    public static int contentLevel(String tier, int learnerLevel) {
        int top = switch (tier) {
            case PRIMARY -> 2;
            case INTERMEDIATE -> 4;
            default -> 7;
        };
        return Math.min(top, learnerLevel);
    }

    /** Fits a sentence for repeating: long enough to mean something, short enough to hold in memory. */
    public static int[] repeatLength(String tier) {
        return PRIMARY.equals(tier) ? new int[] {4, 12} : new int[] {8, 22};
    }

    public static List<Part> build(String tier, Material material, long seed) {
        Random random = new Random(seed);
        List<Part> parts = new ArrayList<>();
        switch (tier) {
            case PRIMARY -> {
                parts.add(new Part("Phần 1: Nghe và nhắc lại", "Nghe một câu rồi nhắc lại đúng câu đó. Mỗi câu chỉ nghe một lần.", 40,
                        fromText(REPEAT, "a", pick(material.lines(), 15, random), true, 0, 0, 10)));
                parts.add(new Part("Phần 2: Nghe và trả lời", "Nghe câu hỏi rồi trả lời ngắn gọn.", 30,
                        fromText(REPLY, "b", pick(PRIMARY_REPLIES, 10, random), true, 5, 0, 10)));
                parts.add(new Part("Phần 3: Trả lời câu hỏi", "Đọc câu hỏi, chuẩn bị rồi trả lời bằng vài câu.", 30,
                        fromText(SPEAK, "c", pick(PRIMARY_OPEN, 2, random), false, 40, 30, 90)));
            }
            case INTERMEDIATE -> {
                parts.add(new Part("Phần 1: Nghe và nhắc lại", "Nghe một câu rồi nhắc lại đúng câu đó. Mỗi câu chỉ nghe một lần.", 40,
                        fromText(REPEAT, "a", pick(material.lines(), 10, random), true, 0, 0, 15)));
                parts.add(new Part("Phần 2: Nói theo tình huống", "Đề thật dùng tranh để bạn miêu tả; bản luyện này dùng tình huống bằng chữ.", 30,
                        fromText(SPEAK, "b", pick(INTERMEDIATE_SITUATIONS, 2, random), false, 100, 60, 120)));
                parts.add(new Part("Phần 3: Trả lời câu hỏi", "Đọc câu hỏi, chuẩn bị rồi trả lời trọn ý.", 30,
                        fromText(SPEAK, "c", pick(INTERMEDIATE_OPEN, 2, random), false, 100, 60, 120)));
            }
            default -> {
                List<String> passages = pick(material.passages(), 4, random);
                List<String> retell = passages.subList(0, Math.max(0, passages.size() - 1));
                List<String> read = passages.subList(Math.max(0, passages.size() - 1), passages.size());
                parts.add(new Part("Phần 1: Nghe và kể lại", "Nghe một đoạn rồi kể lại nội dung bằng lời của bạn.", 40,
                        fromText(RETELL, "a", retell, true, 0, 15, 100)));
                parts.add(new Part("Phần 2: Đọc thành tiếng", "Đọc đoạn văn sau thật rõ ràng.", 20,
                        fromText(READ, "b", read, false, 0, 60, 150)));
                parts.add(new Part("Phần 3: Trả lời câu hỏi", "Đọc câu hỏi, chuẩn bị rồi nêu quan điểm của bạn.", 40,
                        fromText(SPEAK, "c", pick(ADVANCED_OPEN, 2, random), false, 180, 60, 150)));
            }
        }
        return parts;
    }

    private static List<Question> fromText(String kind, String prefix, List<String> texts, boolean listen, int expectedChars, int prepSeconds, int answerSeconds) {
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            int answer = READ.equals(kind) ? Math.max(answerSeconds, hanCount(text) / 2) : answerSeconds;
            questions.add(new Question(prefix + i, kind, listen ? text : null, listen ? null : text, text, expectedChars, prepSeconds, answer));
        }
        return questions;
    }

    private static List<String> pick(List<String> pool, int count, Random random) {
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    /**
     * An answer's score from 0 to 100, from what was heard.
     * Repeating and reading compare with the text; retelling counts how much of the passage's wording came back,
     * and free speech can only be judged on how much was said.
     */
    public static int score(Question question, double accuracy, String transcript) {
        int said = hanCount(transcript);
        if (said == 0) return 0;
        double share = switch (question.kind()) {
            case REPEAT, READ -> accuracy;
            case RETELL -> Math.min(1, coverage(question.reference(), SpeechAlignment.normalize(transcript)) / 0.6);
            default -> Math.min(1, said / (double) Math.max(question.expectedChars(), 1));
        };
        return (int) Math.round(Math.max(0, Math.min(1, share)) * 100);
    }

    /** The share of the passage's different characters that also appear in what was said. */
    static double coverage(String reference, String transcript) {
        List<Integer> distinct = reference.codePoints().filter(HskkPaper::isHan).distinct().boxed().toList();
        if (distinct.isEmpty()) return 0;
        long found = distinct.stream().filter(point -> transcript.indexOf(point) >= 0).count();
        return found / (double) distinct.size();
    }

    public static int hanCount(String text) {
        return text == null ? 0 : (int) text.codePoints().filter(HskkPaper::isHan).count();
    }

    private static boolean isHan(int point) {
        return Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN;
    }

    /** The total out of 100 from each part's average; a question left unanswered counts as 0. */
    public static int total(List<Part> parts, java.util.Map<String, Integer> scores) {
        double total = 0;
        for (Part part : parts) {
            if (part.questions().isEmpty()) continue;
            double sum = part.questions().stream().mapToInt(q -> scores.getOrDefault(q.id(), 0)).sum();
            total += part.weight() * sum / part.questions().size() / 100;
        }
        return (int) Math.round(total);
    }
}
