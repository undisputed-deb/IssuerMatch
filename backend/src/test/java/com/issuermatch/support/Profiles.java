package com.issuermatch.support;

/** A valid institutional investor profile as JSON, for tests. */
public final class Profiles {

    private Profiles() {}

    public static String mandate(String sector, String region, long min, long max) {
        return "{\"sector\":\"" + sector + "\",\"region\":\"" + region + "\",\"minTicket\":" + min + ",\"maxTicket\":" + max
                + ",\"riskLevel\":\"MEDIUM\",\"instruments\":[\"EQUITY\"]}";
    }

    public static String json(String companyName) {
        return json(companyName, "We back payments and lending software in Europe with at least 1M ARR.",
                mandate("FINTECH", "EUROPE", 100_000, 2_000_000));
    }

    public static String json(String companyName, String thesis, String mandates) {
        return "{\"contactName\":\"Ivy Chen\",\"roleTitle\":\"Managing Partner\",\"companyName\":\"" + companyName + "\","
                + "\"websiteUrl\":\"https://example.test/fund\",\"investorType\":\"VC\",\"region\":\"EUROPE\",\"aumOrRevenueUsd\":250000000,"
                + "\"deployableCapitalUsd\":40000000,\"currency\":\"EUR\",\"longThesisDescription\":\"" + thesis + "\",\"mandates\":["
                + mandates + "]}";
    }
}
