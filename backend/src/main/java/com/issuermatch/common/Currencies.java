package com.issuermatch.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * The ISO 4217 currencies the platform accepts, with fixed reference rates to US dollars.
 * The rates are SYNTHETIC and static (set 2026-11). A real system would use a dated FX feed and store the rate used.
 */
public final class Currencies {

    private Currencies() {}

    /** Regular expression for request validation. Must list the same codes as the rate table. */
    public static final String PATTERN = "USD|EUR|GBP|CHF|JPY|INR|SGD|AUD|CAD|AED";

    private static final Map<String, BigDecimal> USD_PER_UNIT = Map.of(
            "USD", new BigDecimal("1"), "EUR", new BigDecimal("1.08"), "GBP", new BigDecimal("1.27"),
            "CHF", new BigDecimal("1.12"), "JPY", new BigDecimal("0.0067"), "INR", new BigDecimal("0.012"),
            "SGD", new BigDecimal("0.74"), "AUD", new BigDecimal("0.66"), "CAD", new BigDecimal("0.73"),
            "AED", new BigDecimal("0.2723"));

    public static BigDecimal toUsd(BigDecimal amount, String currency) {
        BigDecimal rate = USD_PER_UNIT.get(currency);
        if (rate == null) {
            throw new IllegalArgumentException("Unsupported currency " + currency);
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal convert(BigDecimal amount, String from, String to) {
        return from.equals(to) ? amount : toUsd(amount, from).divide(USD_PER_UNIT.get(to), 2, RoundingMode.HALF_UP);
    }

    /** "('USD',1),('EUR',1.08),..." for a SQL VALUES list. Built only from the constants above, never from input. */
    public static String sqlRateValues() {
        return USD_PER_UNIT.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> "('" + e.getKey() + "'," + e.getValue().toPlainString() + ")")
                .collect(java.util.stream.Collectors.joining(","));
    }

    public static boolean isSupported(String currency) {
        return USD_PER_UNIT.containsKey(currency);
    }
}
