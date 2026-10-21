package com.yibi.backend.repository;

import com.yibi.backend.entity.ReadingPassage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReadingPassageRepository extends JpaRepository<ReadingPassage, Long> {

    List<ReadingPassage> findByHskLevel(Integer hskLevel);

    Page<ReadingPassage> findByHskLevel(Integer hskLevel, Pageable pageable);
}
