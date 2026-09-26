package com.fooddelivery.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextMatcherTest {

    @Test
    void analyzesLikeStandardAnalyzerWithAsciiFolding() {
        assertThat(TextMatcher.tokens("Crème Brûlée & Café-Latte")).containsExactly("creme", "brulee", "cafe", "latte");
    }

    @Test
    void fuzzinessAutoEditDistances() {
        assertThat(TextMatcher.fuzzyEquals("biryni", "biryani")).isTrue();   // 6 chars: 2 edits allowed
        assertThat(TextMatcher.fuzzyEquals("panner", "paneer")).isTrue();
        assertThat(TextMatcher.fuzzyEquals("dosa", "dose")).isTrue();        // 4 chars: 1 edit
        assertThat(TextMatcher.fuzzyEquals("dosa", "dish")).isFalse();
        assertThat(TextMatcher.fuzzyEquals("ab", "ac")).isFalse();           // 2 chars: exact only
    }

    @Test
    void everyQueryTokenMustMatch() {
        assertThat(TextMatcher.matchesAll(List.of("chicken", "biryni"), "Chicken Biryani")).isTrue();
        assertThat(TextMatcher.matchesAll(List.of("chicken", "tikka"), "Chicken Biryani")).isFalse();
    }
}
