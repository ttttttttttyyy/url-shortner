package com.url.shortly.service.imp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.validation.UrlValidator;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.LinkResolution;
import com.url.shortly.service.LinkService;
import com.url.shortly.service.ShortCodeGenerator;
import com.url.shortly.service.exeptions.InvalidExpiryException;
import com.url.shortly.service.exeptions.PasswordNotAllowedException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;
@Service 
public class LinkServiceImp implements LinkService{

    private final LinkRepository linkRepository;
    private final ShortCodeGenerator generator;
    final UrlValidator urlValidator;
    private final AppProperties properties;

    public LinkServiceImp(LinkRepository linkRepository, ShortCodeGenerator shortCodeGenerator,
                          UrlValidator urlValidator, AppProperties properties) {
        this.linkRepository = linkRepository;
        this.generator = shortCodeGenerator;
        this.urlValidator = urlValidator;
        this.properties = properties;
    }

    @Override
    public ArrayList<Link> getUserShortLinks() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getUserShortLinks'");
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
        if (link.isExpired()) {
            return new LinkResolution(link, LinkResolution.Outcome.EXPIRED);
        }
        if (link.getPasswordHash() != null) {
            return new LinkResolution(link, LinkResolution.Outcome.PASSWORD_REQUIRED);
        }
        return new LinkResolution(link, LinkResolution.Outcome.ACTIVE);
    }

    @Override
    public Link createLink(CreateLinkRequest request, CallerContext caller) {
        if (request.password() != null) {
            // SoW feature matrix: password-protected links are a paid entitlement,
            // not available to guests or free users.
            if (caller.tier() != PlanTier.PAID) {
                throw new PasswordNotAllowedException("Password-protected links require the paid plan");
            }
            // FR-13 is not implemented yet; never store an unhashed password.
            // Temporary mapping choice: 403 keeps a single status for "you cannot
            // have this feature"; 501 Not Implemented would also be defensible
            // for the paid tier. Revisit when FR-13 lands.
            throw new PasswordNotAllowedException("Password protection is not available yet");
        }

        String destination = urlValidator.validateAndNormalize(request.url());
        Instant expiresAt = validateExpiry(request.expiresAt());

        String alias = request.alias();
        if (alias != null && !alias.isBlank()) {
            generator.validateCustomAlias(alias, caller.tier());
            return insert(alias, destination, caller.userId(), true, expiresAt);
        }

        return generator.generateAndPersist(caller.tier(),
                code -> insert(code, destination, caller.userId(), false, expiresAt));
        }

    /**
     * Apply the configured expiration rules to a requested expiry.
     *
     * <p>{@code null} falls back to {@code app.link.default-expiration-days}
     * (0 = no default expiry, so the link never expires). A non-null value
     * must be in the future and within {@code app.link.max-expiration-days}.
     */
    private Instant validateExpiry(Instant requested) {
        AppProperties.Link config = properties.link();
        Instant now = Instant.now();

        if (requested == null) {
            int defaultDays = config.defaultExpirationDays();
            return defaultDays <= 0 ? null : now.plus(Duration.ofDays(defaultDays));
        }

        if (requested.isBefore(now)) {
            throw new InvalidExpiryException("expiresAt must not be in the past");
        }

        Instant maxExpiry = now.plus(Duration.ofDays(config.maxExpirationDays()));
        if (requested.isAfter(maxExpiry)) {
            throw new InvalidExpiryException(
                    "expiresAt must be within " + config.maxExpirationDays() + " days");
        }
        return requested;
    }
    private Link insert(String code, String destination, Long ownerId,
                        boolean customAlias, Instant expiresAt) {
        Link link = new Link();
        link.setShortCode(code);
        link.setUrl(destination);
        link.setOwnerId(ownerId);
        link.setStatus(LinkStatus.ACTIVE);
        link.setCustomAlias(customAlias);
        link.setExpiresAt(expiresAt);

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
