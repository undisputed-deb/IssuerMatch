package com.issuermatch.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Run with: ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed */
@Component
@Profile("seed")
class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);
    private final SyntheticDataSeeder seeder;

    SeedRunner(SyntheticDataSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info(seeder.seed() ? "Synthetic data created" : "Seed skipped: investors already exist");
    }
}
