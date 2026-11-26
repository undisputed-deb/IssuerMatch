package com.issuermatch.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Full app + MockMvc + throwaway Postgres container. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.ai.google.genai.api-key=test-key-not-real",
        "spring.ai.google.genai.embedding.api-key=test-key-not-real",
        "app.auth.jwks-uri=http://localhost:1/unused-in-tests",
        "app.auth.issuer=https://test.supabase.co/auth/v1",
        "app.auth.audience=authenticated"})
@AutoConfigureMockMvc
@Import(TestInfraConfig.class)
public @interface IntegrationTest {}
