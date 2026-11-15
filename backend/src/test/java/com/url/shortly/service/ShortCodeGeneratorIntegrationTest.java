package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.url.shortly.Entities.Link;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.enums.PlanTier;

/**
 * Verifies the generator against the real persistence layer (H2 in PostgreSQL
 * mode) and the {@code ux_link_short_code} unique index.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShortCodeGeneratorIntegrationTest {

    @Autowired
    private ShortCodeGenerator generator;

    @Autowired
    private LinkRepository linkRepository;

    @BeforeEach
    void cleanDatabase() {
        linkRepository.deleteAll();
    }

    @Test
    @DisplayName("Generated codes are persisted with the tier minimum length")
    void persistsGeneratedCodes() {
        for (PlanTier tier : PlanTier.values()) {
            String code = generator.generate(tier);

            Link link = new Link();
            link.setShortCode(code);
            link.setUrl("https://example.com/" + tier.name().toLowerCase());
            linkRepository.saveAndFlush(link);

            assertThat(code).hasSize(generator.minLength(tier));
            assertThat(linkRepository.findByShortCode(code)).isPresent();
        }
    }

    @Test
    @DisplayName("generateAndPersist never reuses a code")
    void generateAndPersistStoresUniqueLinks() {
        Set<String> codes = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            int index = i;
            Link saved = generator.generateAndPersist(PlanTier.PAID, code -> {
                Link link = new Link();
                link.setShortCode(code);
                link.setUrl("https://example.com/" + index);
                return linkRepository.saveAndFlush(link);
            });
            codes.add(saved.getShortCode());
        }

        assertThat(codes).hasSize(100);
        assertThat(linkRepository.count()).isEqualTo(100);
    }
}
