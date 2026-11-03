package com.yibi.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionBankItemAdminDto {

    private Long id;
    private String questionCode;
    private String questionType;
    private String skillKey;
    private String standardCode;
    private String versionCode;
    private String levelCode;
    private String topicKey;
    private String prompt;
    private String promptPinyin;
    private String promptAudioUrl;
    private String answerText;
    private String explanation;
    private Integer difficulty;
    private String origin;
    private String status;
    private Long lessonId;
    private Long sourceRawRecordId;
    private String contentHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<QuestionBankChoiceAdminDto> choices;
}

