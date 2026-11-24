package com.yibi.backend.dto.grammar;

import java.util.List;

public final class GrammarDtos {

    private GrammarDtos() {}

    /** One example sentence of a note. */
    public record Example(String zh, String pinyin, String vi) {}

    /** The Vietnamese note of a grammar point; status is DRAFT until it has been read and approved. */
    public record Note(String title, String structure, String explanation, List<Example> examples, String status) {}

    /** A grammar point of a lesson as the syllabus gives it, with its note when there is one. */
    public record Point(Long id, String content, String category, String categoryName, String subcategory, Note note) {}
}
