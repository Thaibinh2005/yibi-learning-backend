package com.yibi.backend.repository;

import com.yibi.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** Admin: search users by username or email (paginated) */
    @Query("SELECT u FROM User u WHERE " +
           "LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<User> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /** Admin: toggle active status */
    @Modifying
    @Query("UPDATE User u SET u.isActive = :status WHERE u.id = :id")
    int updateActiveStatus(@Param("id") UUID id, @Param("status") boolean status);

    /** Stats: count users by role */
    @Query("SELECT COUNT(u) FROM User u WHERE u.role = com.yibi.backend.entity.Role.ROLE_USER")
    long countRegularUsers();
}
