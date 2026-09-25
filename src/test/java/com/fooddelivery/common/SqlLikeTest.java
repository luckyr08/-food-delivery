package com.fooddelivery.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLikeTest {

    @Test
    void blankDisablesFilter() {
        assertThat(SqlLike.containsPattern(null)).isNull();
        assertThat(SqlLike.containsPattern("   ")).isNull();
    }

    @Test
    void wrapsAndTrims() {
        assertThat(SqlLike.containsPattern("  spice ")).isEqualTo("%spice%");
    }

    @Test
    void escapesWildcardsAndEscapeChar() {
        assertThat(SqlLike.containsPattern("100%_\\")).isEqualTo("%100\\%\\_\\\\%");
    }
}
