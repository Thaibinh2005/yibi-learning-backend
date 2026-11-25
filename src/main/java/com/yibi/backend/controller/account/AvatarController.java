package com.yibi.backend.controller.account;

import com.yibi.backend.service.AvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * GET /api/avatars/{userId} → the picture a learner uploaded. Public (see SecurityConfig): an {@code <img>} cannot send
 * the access token. The link carries ?v=, which changes with each upload, so the picture may be cached for long.
 */
@RestController
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatarService;

    @GetMapping("/api/avatars/{userId}")
    public ResponseEntity<Resource> avatar(@PathVariable UUID userId) {
        Resource picture = avatarService.load(userId);
        if (picture == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(picture);
    }
}
