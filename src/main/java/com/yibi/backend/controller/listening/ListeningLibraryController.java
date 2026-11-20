package com.yibi.backend.controller.listening;

import com.yibi.backend.dto.common.ApiResponse;
import com.yibi.backend.dto.listening.ListeningDtos.Category;
import com.yibi.backend.dto.listening.ListeningDtos.CategoryPage;
import com.yibi.backend.dto.listening.ListeningDtos.LibraryHome;
import com.yibi.backend.dto.listening.ListeningDtos.VideoSummary;
import com.yibi.backend.exception.UnauthorizedException;
import com.yibi.backend.security.SecurityUtils;
import com.yibi.backend.service.ListeningLibraryService;
import com.yibi.backend.service.ListeningViewer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * The community library, open to guests: published lessons by category. Opening a lesson needs an account
 * ({@code /api/listening/videos/{id}}). A signed-in viewer also gets which lessons they saved.
 */
@RestController
@RequestMapping("/api/listening/library")
@RequiredArgsConstructor
public class ListeningLibraryController {

    private final ListeningLibraryService library;
    private final SecurityUtils securityUtils;

    private ListeningViewer viewerOrGuest() {
        try {
            return ListeningViewer.of(securityUtils.getCurrentUser());
        } catch (UnauthorizedException ex) {
            return ListeningViewer.GUEST;
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LibraryHome>> home(@RequestParam(required = false) String level,
                                                         @RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.success(library.home(viewerOrGuest(), level, q)));
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<Category>>> categories() {
        return ResponseEntity.ok(ApiResponse.success(library.categories(false)));
    }

    @GetMapping("/categories/{slug}")
    public ResponseEntity<ApiResponse<CategoryPage>> category(@PathVariable String slug,
                                                              @RequestParam(required = false) String level,
                                                              @RequestParam(required = false) String q,
                                                              @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(ApiResponse.success(library.category(viewerOrGuest(), slug, level, q, page)));
    }

    /** Published lessons of a YouTube video, so a learner can reuse one instead of adding the video again. */
    @GetMapping("/lookup")
    public ResponseEntity<ApiResponse<List<VideoSummary>>> lookup(@RequestParam String youtube) {
        return ResponseEntity.ok(ApiResponse.success(library.lookup(viewerOrGuest(), youtube)));
    }
}
