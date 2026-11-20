package com.url.shortly.service.imp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.DTO.UpdateLinkRequest;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.validation.UrlValidator;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.LinkResolution;
import com.url.shortly.service.LinkService;
import com.url.shortly.service.OwnedLinks;
import com.url.shortly.service.ShortCodeGenerator;
import com.url.shortly.service.exeptions.InvalidAliasException;
import com.url.shortly.service.exeptions.InvalidExpiryException;
import com.url.shortly.service.exeptions.InvalidLinkPasswordException;
import com.url.shortly.service.exeptions.InvalidLinkUpdateException;
import com.url.shortly.service.exeptions.LinkNotFoundException;
import com.url.shortly.service.exeptions.PasswordNotAllowedException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;
import com.url.shortly.service.exeptions.StatusChangeNotAllowedException;
@Service 
public class LinkServiceImp implements LinkService{

    private static final int SUMMARY_WINDOW_DAYS = 30;

    private final LinkRepository linkRepository;
    private final ShortCodeGenerator generator;
    final UrlValidator urlValidator;
    private final AppProperties properties;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;

    public LinkServiceImp(LinkRepository linkRepository, ShortCodeGenerator shortCodeGenerator,
                          UrlValidator urlValidator, AppProperties properties, Clock clock,
                          PasswordEncoder passwordEncoder) {
        this.linkRepository = linkRepository;
        this.generator = shortCodeGenerator;
        this.urlValidator = urlValidator;
        this.properties = properties;
        this.clock = clock;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * FR-06: the dashboard read. Ownership is enforced by the query key, so
     * another user's links can never appear even if a caller supplies an id.
     * The summary is account-wide and independent of the status filter/page.
     */
    @Override
    public OwnedLinks listOwnedLinks(Long ownerId, int page, int size, LinkStatus status) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        Page<Link> result = status == null
                ? linkRepository.findByOwnerIdAndStatusNot(
                        ownerId, LinkStatus.DELETED, pageable)
                : linkRepository.findByOwnerIdAndStatus(ownerId, status, pageable);

        Instant now = clock.instant();
        return new OwnedLinks(
                result.getContent(),
                result.getTotalElements(),
                result.getTotalPages(),
                usageSummary(ownerId, now),
                now);
    }

    private OwnedLinks.UsageSummary usageSummary(Long ownerId, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(SUMMARY_WINDOW_DAYS));

        List<Object[]> rows = linkRepository.usageSummary(
                ownerId,
                LinkStatus.ACTIVE,
                LinkStatus.DISABLED,
                LinkStatus.BLOCKED,
                LinkStatus.DELETED,
                now,
                cutoff);

        if (rows.isEmpty()) {
            return new OwnedLinks.UsageSummary(0, 0, 0, 0, 0, 0, 0, freeLinkLimit());
        }

        Object[] counts = rows.get(0);
        return new OwnedLinks.UsageSummary(
                number(counts[0]), number(counts[1]), number(counts[2]), number(counts[3]),
                number(counts[4]), number(counts[5]), number(counts[6]), freeLinkLimit());
    }

    private int freeLinkLimit() {
        AppProperties.Plans plans = properties.plans();
        return plans == null || plans.free() == null ? 0 : plans.free().maxLinks();
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /**
     * FR-03 precedence: deleted &gt; blocked &gt; disabled &gt; expired &gt;
     * password &gt; active.
     *
     * <p>Status wins over expiry so a blocked link stays blocked even after its
     * expiry date. Expiry wins over password so an expired link does not ask a
     * stranger to prove knowledge of a secret first; FR-13 must preserve that
     * order. A raw row is never returned to the controller.
     */
    @Override
    public LinkResolution resolve(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode).orElse(null);
        if (link == null) {
            return LinkResolution.notFound();
        }

        if (link.getStatus() == LinkStatus.DELETED || link.getDeletedAt() != null) {
            return new LinkResolution(link, LinkResolution.Outcome.DELETED);
        }
        if (link.getStatus() == LinkStatus.BLOCKED) {
            return new LinkResolution(link, LinkResolution.Outcome.BLOCKED);
        }
        if (link.getStatus() == LinkStatus.DISABLED) {
            return new LinkResolution(link, LinkResolution.Outcome.DISABLED);
        }
        if (link.isExpired(clock.instant())) {
            return new LinkResolution(link, LinkResolution.Outcome.EXPIRED);
        }
        if (link.getPasswordHash() != null) {
            return new LinkResolution(link, LinkResolution.Outcome.PASSWORD_REQUIRED);
        }
        return new LinkResolution(link, LinkResolution.Outcome.ACTIVE);
    }

    @Override
    public Link createLink(CreateLinkRequest request, CallerContext caller) {
        String passwordHash = null;
        if (request.password() != null) {
            // Password protection is a paid entitlement (SoW feature matrix).
            if (caller.tier() != PlanTier.PAID) {
                throw new PasswordNotAllowedException("Password-protected links require the paid plan");
            }
            passwordHash = hashPassword(request.password());
        }

        String destination = urlValidator.validateAndNormalize(request.url());
        Instant expiresAt = validateExpiry(request.expiresAt(), caller.tier());

        String alias = request.alias();
        if (alias != null) {
            if (alias.isBlank()) {
                throw new InvalidAliasException("Alias must not be blank");
            }
            generator.validateCustomAlias(alias, caller.tier());
            return insert(alias, destination, caller.userId(), true, expiresAt, passwordHash);
        }

        final String hashedPassword = passwordHash;
        return generator.generateAndPersist(caller.tier(),
                code -> insert(code, destination, caller.userId(), false, expiresAt, hashedPassword));
        }

    @Override
    @Transactional(readOnly = true)
    public Link getOwnedLink(Long ownerId, Long linkId) {
        return linkRepository.findByIdAndOwnerId(linkId, ownerId)
                // Same predicate as FR-03 resolve(): a soft-deleted row is not
                // a manageable link, whichever field recorded the deletion.
                .filter(link -> link.getStatus() != LinkStatus.DELETED && link.getDeletedAt() == null)
                .orElseThrow(LinkNotFoundException::new);
    }

    /**
     * FR-07/FR-08: destination URL, expiry, the active/disabled switch and (for
     * paid callers) the custom alias are editable. Passwords await FR-13. A
     * BLOCKED link is administered by FR-16, so its status cannot be changed
     * by the owner.
     *
     * <p>Ownership checks and the write share one transaction. Alias conflicts
     * are pre-checked before any mutation; the unique index remains the real
     * authority and a race is translated to {@code 409} without touching the
     * persistence context again, so the whole update rolls back atomically.
     */
    @Override
    @Transactional
    public Link updateOwnedLink(CallerContext caller, Long linkId, UpdateLinkRequest request) {
        Link link = getOwnedLink(caller.userId(), linkId);

        // Validate everything before mutating the entity so a failed update
        // cannot leave a half-applied change in the persistence context.
        String alias = request.alias();
        boolean aliasChange = alias != null && !alias.isBlank();
        if (alias != null && !aliasChange) {
            throw new InvalidLinkUpdateException("alias must not be blank");
        }
        if (aliasChange) {
            // validateCustomAlias enforces the paid-tier entitlement as well as
            // format/length/reserved words, so there is one gate, not two.
            generator.validateCustomAlias(alias, caller.tier());
            if (!alias.equals(link.getShortCode())
                    && linkRepository.existsByShortCode(alias)) {
                throw new ShortCodeConflictException("Short code already taken: " + alias);
            }
        }
        boolean clearPassword = Boolean.TRUE.equals(request.clearPassword());
        if (request.password() != null && clearPassword) {
            throw new InvalidLinkUpdateException("password and clearPassword are mutually exclusive");
        }
        String passwordHash = null;
        if (request.password() != null && caller.tier() != PlanTier.PAID) {
            throw new PasswordNotAllowedException("Password-protected links require the paid plan");
        }
        if (request.password() != null) {
            passwordHash = hashPassword(request.password());
        }
        if (request.url() != null && request.url().isBlank()) {
            throw new InvalidLinkUpdateException("url must not be blank");
        }
        boolean clearExpiry = Boolean.TRUE.equals(request.clearExpiry());
        if (clearExpiry && request.expiresAt() != null) {
            throw new InvalidLinkUpdateException("expiresAt and clearExpiry are mutually exclusive");
        }
        if (request.active() != null && link.getStatus() == LinkStatus.BLOCKED) {
            throw new StatusChangeNotAllowedException();
        }

        String destination = request.url() == null
                ? null
                : urlValidator.validateAndNormalize(request.url());
        Instant expiresAt = request.expiresAt() == null
                ? null
                : validateRequestedExpiry(request.expiresAt(), caller.tier());

        if (destination == null && expiresAt == null && !clearExpiry && !clearPassword
                && passwordHash == null && request.active() == null && !aliasChange) {
            throw new InvalidLinkUpdateException();
        }

        if (aliasChange) {
            link.setShortCode(alias);
            link.setCustomAlias(true);
        }
        if (destination != null) {
            link.setUrl(destination);
        }
        if (clearExpiry) {
            link.setExpiresAt(null);
        } else if (expiresAt != null) {
            link.setExpiresAt(expiresAt);
        }
        if (request.active() != null) {
            link.setStatus(request.active() ? LinkStatus.ACTIVE : LinkStatus.DISABLED);
        }
        if (clearPassword) {
            link.setPasswordHash(null);
        } else if (passwordHash != null) {
            link.setPasswordHash(passwordHash);
        }

        try {
            return linkRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException ex) {
            // The pre-check above handles ordinary duplicates. This branch is
            // only a lost insert race; do not query through the now-poisoned
            // persistence context, just translate and let the transaction roll
            // back all of the changes above.
            if (aliasChange) {
                throw new ShortCodeConflictException("Short code already taken: " + alias, ex);
            }
            throw ex;
        }
    }

    /**
     * FR-07: retention policy is not defined yet, so deletion is a reversible
     * soft delete. The row is hidden from the dashboard and redirects, while
     * the short code stays reserved and the audit trail is preserved.
     */
    @Override
    @Transactional
    public void deleteOwnedLink(Long ownerId, Long linkId) {
        Link link = linkRepository.findByIdAndOwnerId(linkId, ownerId)
                .orElseThrow(LinkNotFoundException::new);

        if (link.getStatus() == LinkStatus.DELETED || link.getDeletedAt() != null) {
            return;
        }
        link.setStatus(LinkStatus.DELETED);
        link.setDeletedAt(clock.instant());
        linkRepository.saveAndFlush(link);
    }

    /**
     * Apply the configured expiration rules to a requested expiry.
     *
     * <p>{@code null} falls back to {@code app.link.default-expiration-days}
     * (0 = no default expiry, so the link never expires). A non-null value must
     * be in the future and within the tier window: the configured maximum for
     * registered accounts, or {@code app.link.guest-retention-days} for guests.
     */
    private Instant validateExpiry(Instant requested, PlanTier tier) {
        if (requested == null) {
            int defaultDays = properties.link().defaultExpirationDays();
            if (defaultDays <= 0) {
                return null;
            }
            // A configured default must still respect the guest retention cap.
            return validateRequestedExpiry(clock.instant().plus(Duration.ofDays(defaultDays)), tier);
        }
        return validateRequestedExpiry(requested, tier);
    }

    /**
     * Validate an explicitly supplied expiry without applying the creation
     * default — {@code null} in a PATCH means "leave unchanged".
     *
     * <p>Registered accounts may use the full configured window. Guests are
     * capped at {@code app.link.guest-retention-days}, applying the FR-04
     * handoff at creation time instead of silently deleting links later.
     */
    private Instant validateRequestedExpiry(Instant requested, PlanTier tier) {
        Instant now = clock.instant();

        if (requested.isBefore(now)) {
            throw new InvalidExpiryException("expiresAt must not be in the past");
        }

        int maxDays = maxExpiryDays(tier);
        if (requested.isAfter(now.plus(Duration.ofDays(maxDays)))) {
            String scope = tier == PlanTier.GUEST ? "Guest links" : "expiresAt";
            throw new InvalidExpiryException(
                    scope + " must be within " + maxDays + " days");
        }
        return requested;
    }

    private String hashPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new InvalidLinkPasswordException("Password must not be blank");
        }
        if (rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) {
            throw new InvalidLinkPasswordException("Password must be at most 128 bytes");
        }
        return passwordEncoder.encode(rawPassword);
    }

    private int maxExpiryDays(PlanTier tier) {
        AppProperties.Link config = properties.link();
        if (tier != PlanTier.GUEST) {
            return config.maxExpirationDays();
        }
        int guestDays = config.guestRetentionDays();
        return guestDays > 0 ? Math.min(guestDays, config.maxExpirationDays())
                : config.maxExpirationDays();
    }
    private Link insert(String code, String destination, Long ownerId,
                        boolean customAlias, Instant expiresAt, String passwordHash) {
        Link link = new Link();
        link.setShortCode(code);
        link.setUrl(destination);
        link.setOwnerId(ownerId);
        link.setStatus(LinkStatus.ACTIVE);
        link.setCustomAlias(customAlias);
        link.setExpiresAt(expiresAt);
        link.setPasswordHash(passwordHash);

        try {
            return linkRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException ex) {
            if (linkRepository.existsByShortCode(code)) {
                throw new ShortCodeConflictException("Short code already taken: " + code, ex);
            }
            throw ex;
        }
    }
    
}
