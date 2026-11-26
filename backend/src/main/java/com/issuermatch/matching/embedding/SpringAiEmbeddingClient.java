package com.issuermatch.matching.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
class SpringAiEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel model;

    SpringAiEmbeddingClient(EmbeddingModel model) {
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = model.embed(text);
        if (vector.length != DIMENSIONS) {
            throw new IllegalStateException("Expected " + DIMENSIONS + " dimensions, got " + vector.length);
        }
        return vector;
    }
}
