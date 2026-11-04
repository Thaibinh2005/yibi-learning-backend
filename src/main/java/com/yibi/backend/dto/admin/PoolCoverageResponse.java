package com.yibi.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** How many PUBLISHED questions each level band and skill has against the target the placement test needs. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolCoverageResponse {

    private boolean ready;
    private int totalMissing;
    private List<Cell> cells;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cell {
        private String levelCode;
        private String skillKey;
        private int published;
        private int target;
        private int missing;
    }
}
