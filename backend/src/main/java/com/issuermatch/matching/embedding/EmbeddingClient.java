package com.issuermatch.matching.embedding;

/** Turns text into a vector. Production uses Gemini; tests use a deterministic fake. */
public interface EmbeddingClient {

    int DIMENSIONS = 768; // must match vector(768) in the database

    float[] embed(String text);
}
