package com.yibi.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionBankPageResponse {

    private List<QuestionBankItemAdminDto> items;
    private long totalElements;
    private int totalPages;
    private int currentPage;
    private int pageSize;
}

