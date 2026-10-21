package com.yibi.backend.repository;

import com.yibi.backend.entity.Vocabulary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VocabularyRepository extends JpaRepository<Vocabulary, Long> {

    List<Vocabulary> findByHskLevel(Integer hskLevel);

    Page<Vocabulary> findByHskLevel(Integer hskLevel, Pageable pageable);

    Optional<Vocabulary> findByHanzi(String hanzi);

    boolean existsByHanzi(String hanzi);

    /** Search by hanzi, pinyin, or meaning (for Admin CMS search bar) */
    @Query("SELECT v FROM Vocabulary v WHERE " +
           "v.hanzi LIKE CONCAT('%', :q, '%') OR " +
           "v.pinyin LIKE CONCAT('%', :q, '%') OR " +
           "LOWER(v.meaningVi) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(v.meaningEn) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<Vocabulary> search(@Param("q") String query, Pageable pageable);

    /** Find vocabulary with no audio URL (for TTS batch generation job) */
    @Query("SELECT v FROM Vocabulary v WHERE v.audioUrl IS NULL")
    List<Vocabulary> findAllWithoutAudio();

    long countByHskLevel(Integer hskLevel);
}
