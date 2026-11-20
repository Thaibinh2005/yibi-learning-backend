package com.yibi.backend.service;

import com.yibi.backend.entity.Role;
import com.yibi.backend.entity.User;

import java.util.UUID;

/** Who is looking at listening lessons: a signed-in learner, an admin, or a guest ({@code id == null}). */
public record ListeningViewer(UUID id, boolean admin) {

    public static final ListeningViewer GUEST = new ListeningViewer(null, false);

    public static ListeningViewer of(User user) {
        return new ListeningViewer(user.getId(), user.getRole() == Role.ROLE_ADMIN);
    }

    public boolean is(UUID other) {
        return id != null && id.equals(other);
    }

    /** Never matches a real user; stands in for a guest in SQL that joins on the viewer. */
    UUID idOrNobody() {
        return id == null ? new UUID(0, 0) : id;
    }
}
