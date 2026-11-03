package com.yibi.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionBankChoiceAdminDto {

    private Long id;
    private String key;
    private Integer order;
    private String text;
    private String audioUrl;
    private boolean correct;
}

