package com.issuermatch.support;

import com.issuermatch.matching.embedding.EmbeddingClient;

/**
 * Deterministic bag-of-words embeddings: each word adds weight to one of 768 slots, then the vector is normalised.
 * Texts that share words are closer, which is enough to test semantic search without any API call.
 */
public class FakeEmbeddingClient implements EmbeddingClient {

    private volatile boolean failing;

    public void failing(boolean on) {
        this.failing = on;
    }

    @Override
    public float[] embed(String text) {
        if (failing) throw new IllegalStateException("embedding provider down");
        float[] v = new float[EmbeddingClient.DIMENSIONS];
        for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() > 2) v[Math.floorMod(word.hashCode(), v.length)] += 1f;
        }
        double norm = 0;
        for (float x : v) norm += x * x;
        if (norm == 0) {
            v[0] = 1f;
            return v;
        }
        for (int i = 0; i < v.length; i++) v[i] /= (float) Math.sqrt(norm);
        return v;
    }
}
