package com.yibi.backend.config;

import com.yibi.backend.entity.*;
import com.yibi.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import org.springframework.core.annotation.Order;

@Slf4j
@Configuration
@Order(2)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        seedUsers();
        seedCoursesAndVocabularies();
    }

    private void seedUsers() {
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = User.builder()
                    .username("admin")
                    .email("admin@yibi.com")
                    .passwordHash(passwordEncoder.encode("Admin@123456"))
                    .role(Role.ROLE_ADMIN)
                    .isActive(true)
                    .hskLevel(6)
                    .build();
            userRepository.save(admin);
            log.info("Default ADMIN user created: admin / Admin@123456");
        }

        if (userRepository.findByUsername("student").isEmpty()) {
            User student = User.builder()
                    .username("student")
                    .email("student@yibi.com")
                    .passwordHash(passwordEncoder.encode("Student@123456"))
                    .role(Role.ROLE_USER)
                    .isActive(true)
                    .hskLevel(1)
                    .build();
            userRepository.save(student);
            log.info("Default USER created: student / Student@123456");
        }
    }

    private void seedCoursesAndVocabularies() {
        if (vocabularyRepository.count() == 0) {
            vocabularyRepository.saveAll(List.of(
                    Vocabulary.builder().hanzi("你").hanziTraditional("你").pinyin("nǐ").meaningVi("Bạn, anh, chị (ngôi thứ 2)").meaningEn("you").hskLevel(1).partOfSpeech("pronoun").exampleSentence("你好！").examplePinyin("Nǐ hǎo!").build(),
                    Vocabulary.builder().hanzi("好").hanziTraditional("好").pinyin("hǎo").meaningVi("Tốt, khỏe, đẹp").meaningEn("good, fine").hskLevel(1).partOfSpeech("adjective").exampleSentence("很好。").examplePinyin("Hěn hǎo.").build(),
                    Vocabulary.builder().hanzi("您").hanziTraditional("您").pinyin("nín").meaningVi("Ngài, ông, bà (kính ngữ)").meaningEn("you (polite)").hskLevel(1).partOfSpeech("pronoun").exampleSentence("老师，您好！").examplePinyin("Lǎoshī, nín hǎo!").build(),
                    Vocabulary.builder().hanzi("谢谢").hanziTraditional("謝謝").pinyin("xièxie").meaningVi("Cảm ơn").meaningEn("thank you").hskLevel(1).partOfSpeech("verb").exampleSentence("不客气，谢谢你！").examplePinyin("Bù kèqì, xièxie nǐ!").build(),
                    Vocabulary.builder().hanzi("再见").hanziTraditional("再見").pinyin("zàijiàn").meaningVi("Tạm biệt").meaningEn("goodbye").hskLevel(1).partOfSpeech("verb").exampleSentence("明天再见！").examplePinyin("Míngtiān zàijiàn!").build(),
                    Vocabulary.builder().hanzi("叫").hanziTraditional("叫").pinyin("jiào").meaningVi("Tên là, gọi là").meaningEn("to be called").hskLevel(1).partOfSpeech("verb").exampleSentence("你叫什么名字？").examplePinyin("Nǐ jiào shénme míngzì?").build(),
                    Vocabulary.builder().hanzi("什么").hanziTraditional("甚麼").pinyin("shénme").meaningVi("Cái gì, gì").meaningEn("what").hskLevel(1).partOfSpeech("pronoun").exampleSentence("这是什么？").examplePinyin("Zhè shì shénme?").build(),
                    Vocabulary.builder().hanzi("名字").hanziTraditional("名字").pinyin("míngzi").meaningVi("Tên, họ tên").meaningEn("name").hskLevel(1).partOfSpeech("noun").exampleSentence("我的名字叫王平。").examplePinyin("Wǒ de míngzi jiào Wáng Píng.").build(),
                    Vocabulary.builder().hanzi("我").hanziTraditional("我").pinyin("wǒ").meaningVi("Tôi, tao (ngôi thứ 1)").meaningEn("I, me").hskLevel(1).partOfSpeech("pronoun").exampleSentence("我是学生。").examplePinyin("Wǒ shì xuésheng.").build(),
                    Vocabulary.builder().hanzi("是").hanziTraditional("是").pinyin("shì").meaningVi("Là, phải, đúng").meaningEn("to be, yes").hskLevel(1).partOfSpeech("verb").exampleSentence("他是中国人。").examplePinyin("Tā shì Zhōngguórén.").build(),
                    Vocabulary.builder().hanzi("今天").hanziTraditional("今天").pinyin("jīntiān").meaningVi("Hôm nay").meaningEn("today").hskLevel(2).partOfSpeech("noun").exampleSentence("今天天气很好。").examplePinyin("Jīntiān tiānqì hěn hǎo.").build(),
                    Vocabulary.builder().hanzi("天气").hanziTraditional("天氣").pinyin("tiānqì").meaningVi("Thời tiết").meaningEn("weather").hskLevel(2).partOfSpeech("noun").exampleSentence("北京的天气怎么样？").examplePinyin("Běijīng de tiānqì zěnmeyàng?").build(),
                    Vocabulary.builder().hanzi("怎么样").hanziTraditional("怎麼樣").pinyin("zěnmeyàng").meaningVi("Thế nào, ra sao").meaningEn("how is it").hskLevel(2).partOfSpeech("pronoun").exampleSentence("你觉得怎么样？").examplePinyin("Nǐ juéde zěnmeyàng?").build()
            ));
            log.info("Seeded 13 core HSK 1 & 2 vocabularies.");
        }

        if (courseRepository.count() == 0) {
            Course hsk1 = Course.builder()
                    .title("Tiếng Trung Nhập Môn HSK 1")
                    .description("Khóa học nền tảng cho người mới bắt đầu: Ngữ âm Pinyin, quy tắc viết chữ Hán và 150 từ vựng cốt lõi HSK 1.")
                    .hskLevel(1)
                    .totalLessons(2)
                    .build();
            hsk1 = courseRepository.save(hsk1);

            Lesson l1 = Lesson.builder()
                    .course(hsk1)
                    .title("Bài 1: Chào hỏi cơ bản - 你好")
                    .lessonOrder(1)
                    .contentType(LessonContentType.VOCABULARY)
                    .contentRefId(1L)
                    .build();

            Lesson l2 = Lesson.builder()
                    .course(hsk1)
                    .title("Bài 2: Giới thiệu bản thân - 我叫李明")
                    .lessonOrder(2)
                    .contentType(LessonContentType.VOCABULARY)
                    .contentRefId(6L)
                    .build();

            lessonRepository.saveAll(List.of(l1, l2));

            Course hsk2 = Course.builder()
                    .title("Tiếng Trung Sơ Cấp HSK 2")
                    .description("Nâng cao phản xạ giao tiếp hàng ngày, mua sắm, hỏi đường, thời tiết và đạt mốc 300 từ vựng.")
                    .hskLevel(2)
                    .totalLessons(1)
                    .build();
            hsk2 = courseRepository.save(hsk2);

            Lesson hsk2_l1 = Lesson.builder()
                    .course(hsk2)
                    .title("Bài 1: Thời tiết hôm nay thế nào? - 今天天气怎么样？")
                    .lessonOrder(1)
                    .contentType(LessonContentType.VOCABULARY)
                    .contentRefId(11L)
                    .build();
            lessonRepository.save(hsk2_l1);

            log.info("Seeded 2 Courses and 3 Lessons.");
        }
    }
}
