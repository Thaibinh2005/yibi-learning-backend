package com.yibi.backend.repository;

import com.yibi.backend.entity.GrammarPoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GrammarPointRepository extends JpaRepository<GrammarPoint, Long> {

    List<GrammarPoint> findByHskLevel(Integer hskLevel);

    Page<GrammarPoint> findByHskLevel(Integer hskLevel, Pageable pageable);

    @Query("SELECT g FROM GrammarPoint g WHERE " +
           "LOWER(g.title) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(g.pattern) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<GrammarPoint> search(@Param("q") String query, Pageable pageable);
}
