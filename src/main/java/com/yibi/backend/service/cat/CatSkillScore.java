package com.yibi.backend.service.cat;

public record CatSkillScore(String skill, int attempts, int correct, double theta, double standardError) {
}
