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
import com.url.shortly.Validation.UrlValidator;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.LinkService;
import com.url.shortly.service.ShortCodeGenerator;
import com.url.shortly.service.exeptions.InvalidExpiryException;
import com.url.shortly.service.exeptions.InvalidShortCodeException;
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

    @Override
    public Link getOriginalLink(String shortCode) {
        return linkRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new InvalidShortCodeException(shortCode));
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
