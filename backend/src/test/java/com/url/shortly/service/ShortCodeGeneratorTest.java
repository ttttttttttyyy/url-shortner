package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.exeptions.InvalidAliasException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;
import com.url.shortly.service.exeptions.ShortCodeGenerationException;

/**
 * Unit tests for the short-code algorithm.
 *
 * <p>These tests use the package-private constructor with a seeded
 * {@link Random}, so they are deterministic and do not touch the database.
 * The unique-index behaviour is covered by
 * {@link ShortCodeGeneratorIntegrationTest}.
 */
class ShortCodeGeneratorTest {

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    @Test
    @DisplayName("Generated codes start at the tier minimum length")
    void tierMinimumLengths() {
        ShortCodeGenerator generator = generator(config(BASE62, 6, 5, 4, 32, 10), 1);

        assertThat(generator.generate(PlanTier.GUEST, code -> true)).hasSize(6);
        assertThat(generator.generate(PlanTier.FREE, code -> true)).hasSize(5);
        assertThat(generator.generate(PlanTier.PAID, code -> true)).hasSize(4);
    }

    @Test
    @DisplayName("Generated codes use only the configured alphabet")
    void alphabetIsRespected() {
        ShortCodeGenerator generator = generator(config(BASE62, 4, 4, 4, 4, 10), 1);

        for (int i = 0; i < 200; i++) {
            assertThat(generator.generate(PlanTier.PAID, code -> true))
                    .matches("[0-9A-Za-z]{4}");
        }
    }

    @Test
    @DisplayName("A code rejected by the availability check is skipped")
    void skipsUnavailableCodes() {
        ShortCodeGenerator generator = generator(config("ab", 1, 1, 1, 1, 100), 7);

        String code = generator.generate(PlanTier.PAID, "b"::equals);

        assertThat(code).isEqualTo("b");
    }

    @Test
    @DisplayName("The generator grows the length when shorter codes are exhausted")
    void growsLengthOnExhaustion() {
        ShortCodeGenerator generator = generator(config("ab", 1, 1, 1, 2, 3), 3);

        String code = generator.generate(PlanTier.PAID, candidate -> candidate.length() == 2);

        assertThat(code).hasSize(2).matches("[ab]{2}");
    }

    @Test
    @DisplayName("The generator fails clearly when the whole configured space is exhausted")
    void throwsWhenSpaceIsExhausted() {
        ShortCodeGenerator generator = generator(config("ab", 1, 1, 1, 1, 3), 1);

        assertThatThrownBy(() -> generator.generate(PlanTier.PAID, code -> false))
                .isInstanceOf(ShortCodeGenerationException.class)
                .hasMessageContaining("No free short code");
    }

    @Test
    @DisplayName("Reserved words are matched case-insensitively")
    void reservedWordsAreCaseInsensitive() {
        ShortCodeGenerator generator = generator(config(BASE62, 4, 4, 4, 32, 10, "api", "admin"), 1);

        assertThat(generator.isReserved("api")).isTrue();
        assertThat(generator.isReserved("API")).isTrue();
        assertThat(generator.isReserved("Api")).isTrue();
        assertThat(generator.isReserved("apix")).isFalse();
        assertThat(generator.isReserved(null)).isFalse();
    }

    @Test
    @DisplayName("Reserved words are never generated")
    void reservedWordsAreNeverGenerated() {
        ShortCodeGenerator generator = generator(config("ab", 1, 1, 1, 1, 100, "a"), 1);

        String code = generator.generate(PlanTier.PAID, candidate -> true);

        assertThat(code).isEqualTo("b");
    }

    @Test
    @DisplayName("1,000 simulated reservations produce distinct codes")
    void generatedCodesAreDistinct() {
        ShortCodeGenerator generator = generator(config(BASE62, 6, 5, 4, 32, 10), 1);
        Set<String> issued = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            String code = generator.generate(PlanTier.PAID, issued::add);
            assertThat(code).hasSize(4);
        }

        assertThat(issued).hasSize(1_000);
    }

    @Test
    @DisplayName("A lost insert race is retried with a fresh code")
    void retriesAfterConflict() {
        ShortCodeGenerator generator = generator(config("ab", 1, 1, 1, 1, 100), 1);
        AtomicInteger calls = new AtomicInteger();

        String result = generator.generateAndPersist(PlanTier.PAID, code -> true, code -> {
            if (calls.getAndIncrement() == 0) {
                throw new ShortCodeConflictException("short_code already exists");
            }
            return code;
        });

        assertThat(result).matches("[ab]");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("Conflict retries stop at the configured limit")
    void conflictRetriesStopAtLimit() {
        ShortCodeGenerator generator = generator(config("ab", 1, 1, 1, 1, 3), 1);

        assertThatThrownBy(() -> generator.generateAndPersist(
                PlanTier.PAID,
                code -> true,
                code -> {
                    throw new ShortCodeConflictException("short_code already exists");
                }))
                .isInstanceOf(ShortCodeGenerationException.class);
    }

    @Test
    @DisplayName("Custom aliases require the paid tier (FR-08)")
    void aliasRequiresPaidTier() {
        ShortCodeGenerator generator = generator(config(BASE62, 6, 5, 4, 32, 10, "api"), 1);

        assertThatThrownBy(() -> generator.validateCustomAlias("mylink", PlanTier.GUEST))
                .isInstanceOf(InvalidAliasException.class)
                .hasMessageContaining("paid plan");
        assertThatThrownBy(() -> generator.validateCustomAlias("mylink", PlanTier.FREE))
                .isInstanceOf(InvalidAliasException.class)
                .hasMessageContaining("paid plan");

        generator.validateCustomAlias("mylink", PlanTier.PAID);
    }

    @Test
    @DisplayName("Custom alias format and length are validated (FR-08)")
    void aliasFormat() {
        ShortCodeGenerator generator = generator(config(BASE62, 6, 5, 4, 32, 10, "api"), 1);

        generator.validateCustomAlias("a", PlanTier.PAID);
        generator.validateCustomAlias("my-link_2", PlanTier.PAID);

        for (String invalid : List.of("", "-abc", "abc-", "_abc", "abc_", "ab cd", "abc!", "abc.def", "é")) {
            assertThatThrownBy(() -> generator.validateCustomAlias(invalid, PlanTier.PAID))
                    .as("alias %s", invalid)
                    .isInstanceOf(InvalidAliasException.class);
        }

        assertThatThrownBy(() -> generator.validateCustomAlias("a".repeat(33), PlanTier.PAID))
                .isInstanceOf(InvalidAliasException.class);
    }

    @Test
    @DisplayName("Reserved aliases are rejected (FR-08)")
    void aliasReserved() {
        ShortCodeGenerator generator = generator(config(BASE62, 6, 5, 4, 32, 10, "api", "admin"), 1);

        assertThatThrownBy(() -> generator.validateCustomAlias("api", PlanTier.PAID))
                .isInstanceOf(InvalidAliasException.class)
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> generator.validateCustomAlias("ADMIN", PlanTier.PAID))
                .isInstanceOf(InvalidAliasException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("Invalid configuration fails fast at startup")
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> generator(config("a", 1, 1, 1, 1, 10), 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator(config("aa", 1, 1, 1, 1, 10), 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator(config("a!", 1, 1, 1, 1, 10), 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator(config(BASE62, 0, 1, 1, 1, 10), 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator(config(BASE62, 1, 1, 1, 0, 10), 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator(config(BASE62, 1, 1, 1, 33, 10), 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator(config(BASE62, 1, 1, 1, 1, 0), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AppProperties.ShortCode config(
            String alphabet, int guest, int free, int paid, int maxLength, int attempts, String... reserved) {
        return new AppProperties.ShortCode(
                guest, free, paid, maxLength, alphabet, attempts, List.of(reserved));
    }

    private static ShortCodeGenerator generator(AppProperties.ShortCode config, long seed) {
        return new ShortCodeGenerator(config, null, new Random(seed));
    }
}
