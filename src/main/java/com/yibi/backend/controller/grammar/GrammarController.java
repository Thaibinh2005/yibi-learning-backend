package com.yibi.backend.controller.grammar;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.grammar.GrammarDtos.Point;
import com.yibi.backend.service.GrammarNotesService;
import com.yibi.backend.service.LessonAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/grammar")
@RequiredArgsConstructor
public class GrammarController {

    private final GrammarNotesService grammarNotesService;
    private final LessonAccessService lessonAccess;

    /** The grammar points of one lesson with their notes, for the library; open to guests like the rest of it. */
    @GetMapping("/lesson/{lessonId}")
    public ResponseEntity<ApiResponse<List<Point>>> getLessonGrammar(@PathVariable Long lessonId) {
        lessonAccess.requireLesson(lessonId);
        return ResponseEntity.ok(ApiResponse.success(grammarNotesService.forLesson(lessonId)));
    }
}
