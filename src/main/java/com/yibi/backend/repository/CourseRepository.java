package com.yibi.backend.repository;

import com.yibi.backend.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByHskLevelOrderByHskLevelAsc(Integer hskLevel);
}
