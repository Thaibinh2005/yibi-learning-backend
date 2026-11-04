package com.yibi.backend.service.cat;

/** Ability on the HSK scale (level 3 is 3.0, the 7-9 band is 8.0) and its standard error. */
public record CatEstimate(double theta, double standardError) {
}
