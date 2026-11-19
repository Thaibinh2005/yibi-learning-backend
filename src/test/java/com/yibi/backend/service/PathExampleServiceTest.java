package com.yibi.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathExampleServiceTest {

    @Test
    void aSentenceWithoutPinyinGetsTheDictionaryReadingOfEachCharacter() {
        assertThat(PathExampleService.reading("我们是朋友。")).isEqualTo("wǒ men shì péng yǒu。");
    }

    @Test
    void lettersAndDigitsInTheSentenceAreKept() {
        assertThat(PathExampleService.reading("他有3个苹果")).isEqualTo("tā yǒu 3 gè píng guǒ");
    }
}
