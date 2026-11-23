package com.url.shortly.service;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.exeptions.AliasNotAllowedException;
import com.url.shortly.service.exeptions.InvalidAliasException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;
import com.url.shortly.service.exeptions.ShortCodeGenerationException;

/**
 * Generates and validates short codes.
 *
 * <p>Generated codes are drawn from a {@link SecureRandom} over the configured
 * alphabet. Generation starts at the caller tier's minimum length and grows
 * the length by one when every attempt at the current length collides.
 * Reserved words are never returned.
 *
 * <p><strong>Uniqueness:</strong> {@link #generate(PlanTier)} pre-checks the
 * database through {@link LinkRepository#existsByShortCode(String)}, but the
 * unique index on {@code link.short_code} is the authority. When persisting a
 * code, use {@link #generateAndPersist(PlanTier, Function)} so a lost insert
 * race is retried with a fresh candidate.
 */
@Component
public class ShortCodeGenerator {

    /** Custom aliases may be shorter than generated codes; FR-08 sets no floor. */
    static final int MIN_ALIAS_LENGTH = 1;

    private static final Pattern ALIAS_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private final AppProperties.ShortCode config;
    private final LinkRepository linkRepository;
    private final RandomGenerator random;
    private final Set<String> reservedAliases;

    @Autowired
    public ShortCodeGenerator(AppProperties properties, LinkRepository linkRepository) {
        this(properties.shortCode(), linkRepository, new SecureRandom());
    }

    /** Test seam: allows a seeded {@link RandomGenerator} and a null repository. */
    ShortCodeGenerator(AppProperties.ShortCode config, LinkRepository linkRepository, RandomGenerator random) {
        this.config = Objects.requireNonNull(config, "config");
        this.linkRepository = linkRepository;
        this.random = Objects.requireNonNull(random, "random");
        validateConfig(config);
        this.reservedAliases = config.reservedAliases().stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Return a free code for the tier, starting at the tier's minimum length.
     *
     * <p>The returned code is not yet persisted. Prefer
     * {@link #generateAndPersist(PlanTier, Function)} when creating a link.
     *
     * @throws ShortCodeGenerationException when the configured space is
     *         exhausted or every attempt collides
     */
    public String generate(PlanTier tier) {
        Objects.requireNonNull(linkRepository, "linkRepository");
        return generate(tier, code -> !linkRepository.existsByShortCode(code));
    }

    /**
     * Generate a code, hand it to {@code persister}, and retry with a fresh
     * code if the persister reports a unique-constraint conflict.
     *
     * <p>The persister must run the insert in its own transaction (for example
     * {@code @Transactional(propagation = REQUIRES_NEW)}) so a rejected insert
     * does not mark the caller's transaction rollback-only. It should throw
     * {@link ShortCodeConflictException} only for the
     * {@code ux_link_short_code} constraint; other failures propagate.
     */
    public <T> T generateAndPersist(PlanTier tier, Function<String, T> persister) {
        Objects.requireNonNull(linkRepository, "linkRepository");
        return generateAndPersist(tier, code -> !linkRepository.existsByShortCode(code), persister);
    }

    /**
     * Validate a requested custom alias for the tier (FR-08).
     *
     * <p>Uniqueness is not checked here: the database unique index is the
     * authority and should surface as {@code 409 Conflict}. This method only
     * covers entitlement, length, format and reserved words, which map to
     * {@code 403} or {@code 422}.
     *
     * @throws InvalidAliasException when the alias is not allowed
     */
    public void validateCustomAlias(String alias, PlanTier tier) {
        Objects.requireNonNull(tier, "tier");
        if (tier != PlanTier.PAID) {
            throw new AliasNotAllowedException("Custom aliases require the paid plan");
        }
        if (alias == null) {
            throw new InvalidAliasException("Alias must not be null");
        }
        if (alias.length() < MIN_ALIAS_LENGTH || alias.length() > config.maxLength()) {
            throw new InvalidAliasException(
                    "Alias must be between " + MIN_ALIAS_LENGTH + " and " + config.maxLength() + " characters");
        }
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new InvalidAliasException(
                    "Alias must start with a letter or digit and contain only letters, digits, '-' or '_'");
        }
        char last = alias.charAt(alias.length() - 1);
        if (last == '-' || last == '_') {
            throw new InvalidAliasException("Alias must end with a letter or digit");
        }
        if (isReserved(alias)) {
            throw new InvalidAliasException("Alias is reserved");
        }
    }

    /** Reserved words are matched case-insensitively. */
    public boolean isReserved(String code) {
        return code != null && reservedAliases.contains(code.toLowerCase(Locale.ROOT));
    }

    /** Minimum generated-code length for the tier. */
    public int minLength(PlanTier tier) {
        return switch (Objects.requireNonNull(tier, "tier")) {
            case GUEST -> config.guestMinLength();
            case FREE -> config.freeMinLength();
            case PAID -> config.paidMinLength();
        };
    }

    /** Absolute generated-code length ceiling. */
    public int maxLength() {
        return config.maxLength();
    }

    String generate(PlanTier tier, Predicate<String> available) {
        return allocate(tier, available, Function.identity());
    }

    <T> T generateAndPersist(PlanTier tier, Predicate<String> available, Function<String, T> persister) {
        return allocate(tier, available, persister);
    }

    private <T> T allocate(PlanTier tier, Predicate<String> available, Function<String, T> onCandidate) {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(onCandidate, "onCandidate");
        int from = minLength(tier);

        for (int length = from; length <= config.maxLength(); length++) {
            for (int attempt = 0; attempt < config.maxGenerationAttempts(); attempt++) {
                String code = randomCode(length);
                if (isReserved(code) || !available.test(code)) {
                    continue;
                }
                try {
                    return onCandidate.apply(code);
                } catch (ShortCodeConflictException conflict) {
                    // The code was claimed between the pre-check and the insert.
                }
            }
        }

        throw new ShortCodeGenerationException("No free short code found for tier " + tier
                + " between lengths " + from + " and " + config.maxLength()
                + " after " + config.maxGenerationAttempts() + " attempts per length");
    }

    /**
     * Draw uniformly from the alphabet using rejection sampling. A plain
     * {@code byte % base} would bias the first {@code 256 % base} symbols.
     */
    private String randomCode(int length) {
        int base = config.alphabet().length();
        int limit = 256 - (256 % base);
        char[] chars = new char[length];
        int index = 0;

        while (index < length) {
            int value = random.nextInt(256);
            if (value < limit) {
                chars[index++] = config.alphabet().charAt(value % base);
            }
        }
        return new String(chars);
    }

    private static void validateConfig(AppProperties.ShortCode config) {
        String alphabet = config.alphabet();
        if (alphabet == null || alphabet.length() < 2 || alphabet.length() > 256) {
            throw new IllegalStateException("short-code alphabet must contain between 2 and 256 characters");
        }

        Set<Character> seen = new HashSet<>();
        for (char c : alphabet.toCharArray()) {
            if (!isAsciiAlphanumeric(c)) {
                throw new IllegalStateException(
                        "short-code alphabet must contain only ASCII letters and digits: " + c);
            }
            if (!seen.add(c)) {
                throw new IllegalStateException("short-code alphabet contains a duplicate character: " + c);
            }
        }

        if (config.maxLength() < 1 || config.maxLength() > 32) {
            throw new IllegalStateException("short-code max-length must be between 1 and 32");
        }
        if (config.maxGenerationAttempts() < 1) {
            throw new IllegalStateException("short-code max-generation-attempts must be at least 1");
        }
        validateTierLength("guest", config.guestMinLength(), config.maxLength());
        validateTierLength("free", config.freeMinLength(), config.maxLength());
        validateTierLength("paid", config.paidMinLength(), config.maxLength());
    }

    private static void validateTierLength(String tier, int length, int maxLength) {
        if (length < 1 || length > maxLength) {
            throw new IllegalStateException(
                    "short-code " + tier + " minimum length must be between 1 and " + maxLength);
        }
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
