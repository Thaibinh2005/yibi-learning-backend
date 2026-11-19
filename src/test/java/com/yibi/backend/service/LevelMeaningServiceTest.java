package com.yibi.backend.service;

import com.yibi.backend.dto.vocabulary.LevelMeaningResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LevelMeaningServiceTest {

    private final LevelMeaningService service = new LevelMeaningService();

    @Test
    void givesEachLevelTheMeaningTheSyllabusIntroducesThere() {
        assertThat(service.find("分", "1", "fēn")).map(LevelMeaningResponse::getMain).contains("phút");
        Optional<LevelMeaningResponse> hsk3 = service.find("分", "3", "fēn");
        assertThat(hsk3).map(LevelMeaningResponse::getMain).contains("chia");
        assertThat(hsk3).map(LevelMeaningResponse::getPartOfSpeech).contains("动、名");
        assertThat(hsk3).map(LevelMeaningResponse::getSyllabusVersion).contains("HSK3.0-2025-11");
    }

    @Test
    void keepsTheDictionaryMeaningOutsideTheOfficialList() {
        // 分 is not in the official list at HSK 2, and the reading fèn is not the official fēn.
        assertThat(service.find("分", "2", "fēn")).isEmpty();
        assertThat(service.find("分", "3", "fèn")).isEmpty();
        assertThat(service.find("不存在", "1", "bù")).isEmpty();
    }

    @Test
    void tellsHomographsApartByPinyin() {
        assertThat(service.find("长", "2", "cháng")).map(LevelMeaningResponse::getMain).contains("dài");
        assertThat(service.find("长", "2", "zhǎng")).isEmpty();
        assertThat(service.find("长2", "3", "zhǎng")).isPresent();
    }

    @Test
    void listsWhatLowerLevelsTaughtOfTheSameWord() {
        LevelMeaningResponse hsk3 = service.find("分", "3", "fēn").orElseThrow();
        assertThat(hsk3.getEarlierLevels()).extracting(LevelMeaningResponse::getLevel).containsExactly("1");
        assertThat(hsk3.getEarlierLevels().get(0).getMain()).isEqualTo("phút");
        assertThat(service.find("分", "1", "fēn").orElseThrow().getEarlierLevels()).isEmpty();
        // 长 zhǎng (HSK 3) is another word than 长 cháng (HSK 2).
        assertThat(service.find("长", "3", "zhǎng").orElseThrow().getEarlierLevels()).isEmpty();
    }

    @Test
    void acceptsNeutralTonesAndTheToneChangeOfYiAndBu() {
        assertThat(LevelMeaningService.samePinyin("yíhuìr", "yīhuìr")).isTrue();
        assertThat(LevelMeaningService.samePinyin("bú kèqi", "bù kè qi")).isTrue();
        assertThat(LevelMeaningService.samePinyin("gēge", "gēgē")).isTrue();
        assertThat(LevelMeaningService.samePinyin("lǜsè", "lǜ sè")).isTrue();
        assertThat(LevelMeaningService.samePinyin("fēn", "fèn")).isFalse();
        assertThat(LevelMeaningService.samePinyin("yín", "yìn")).isFalse();
    }
}
