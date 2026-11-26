package com.issuermatch.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StrictStringsTest {

    static String of(int... chars) {
        StringBuilder sb = new StringBuilder();
        for (int c : chars) sb.append((char) c);
        return sb.toString();
    }

    @Test
    void ordinaryTextEmojiAndAccentsAreFine() {
        assertThat(StrictStrings.isStorable("")).isTrue();
        assertThat(StrictStrings.isStorable("Caf" + of(0xE9) + " " + of(0xD83D, 0xDE80) + "\n\t")).isTrue();
    }

    @Test
    void nulAndUnpairedSurrogatesAreNot() {
        assertThat(StrictStrings.isStorable("a" + of(0) + "b")).isFalse();
        assertThat(StrictStrings.isStorable("x" + of(0xD800))).isFalse();          // high surrogate at the end
        assertThat(StrictStrings.isStorable(of(0xD800) + "x")).isFalse();          // high without low
        assertThat(StrictStrings.isStorable(of(0xDC00) + "x")).isFalse();          // low without high
    }
}
