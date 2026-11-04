package com.yibi.backend.service.cat;

/** One question as the engine sees it: its skill, topic and IRT 2PL parameters (a = discrimination, b = difficulty on the HSK scale). */
public record CatItem(long id, String skill, String topic, double a, double b) {
}
