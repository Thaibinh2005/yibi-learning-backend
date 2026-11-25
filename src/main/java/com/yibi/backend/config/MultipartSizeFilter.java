package com.yibi.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The multipart limit is raised for learners' media uploads; every other upload (speaking recordings) keeps the old
 * 6 MB cap, checked here before the body is read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MultipartSizeFilter extends OncePerRequestFilter {

    static final long SMALL_LIMIT = 6L * 1024 * 1024;
    /** Learners' videos, and an admin's sample paper (PDF + full recording). */
    static final Set<String> LARGE_UPLOADS = Set.of("/api/listening/videos/upload", "/api/admin/mock-tests/imports",
            "/api/admin/mock-tests/inspect");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        if (type != null && type.toLowerCase().startsWith("multipart/")
                && !LARGE_UPLOADS.contains(request.getRequestURI())
                && request.getContentLengthLong() > SMALL_LIMIT) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"success\":false,\"message\":\"File quá lớn (tối đa 6 MB).\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
