package com.yibi.backend.controller.hanzi;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.hanzi.HanziSyllableResponse;
import com.yibi.backend.service.HanziService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hanzi")
@RequiredArgsConstructor
public class HanziController {

    private final HanziService hanziService;

    /** Characters for a syllable; an empty `initial` means none, a missing parameter means any. */
    @GetMapping("/syllables")
    public ResponseEntity<ApiResponse<List<HanziSyllableResponse>>> bySyllable(
            @RequestParam(required = false) String initial,
            @RequestParam(name = "final", required = false) String finalSound,
            @RequestParam(required = false) Integer tone) {
        return ResponseEntity.ok(ApiResponse.success(hanziService.bySyllable(initial, finalSound, tone)));
    }

    @GetMapping("/writing")
    public ResponseEntity<ApiResponse<List<HanziSyllableResponse>>> writing(@RequestParam(required = false) String level) {
        return ResponseEntity.ok(ApiResponse.success(hanziService.writing(level)));
    }
}
