package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.url.shortly.Entities.Link;
import com.url.shortly.service.LinkResolution.Outcome;

/**
 * FR-03 value-object invariants: invalid link/outcome combinations must be
 * unrepresentable, and only ACTIVE may redirect.
 */
class LinkResolutionTest {

    @Test
    @DisplayName("NOT_FOUND carries no link")
    void notFoundHasNoLink() {
        LinkResolution resolution = LinkResolution.notFound();

        assertThat(resolution.outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(resolution.link()).isNull();
        assertThat(resolution.redirects()).isFalse();
    }

    @Test
    @DisplayName("every non-NOT_FOUND outcome carries the resolved row")
    void nonNotFoundCarriesLink() {
        Link link = new Link();

        assertThat(new LinkResolution(link, Outcome.ACTIVE).redirects()).isTrue();
        assertThat(new LinkResolution(link, Outcome.EXPIRED).redirects()).isFalse();
        assertThat(new LinkResolution(link, Outcome.DELETED).redirects()).isFalse();
        assertThat(new LinkResolution(link, Outcome.DISABLED).redirects()).isFalse();
        assertThat(new LinkResolution(link, Outcome.BLOCKED).redirects()).isFalse();
        assertThat(new LinkResolution(link, Outcome.PASSWORD_REQUIRED).redirects()).isFalse();
    }

    @Test
    @DisplayName("a non-NOT_FOUND outcome without a link is rejected")
    void nonNotFoundWithoutLinkIsRejected() {
        assertThatThrownBy(() -> new LinkResolution(null, Outcome.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link presence");
    }

    @Test
    @DisplayName("NOT_FOUND with a link is rejected")
    void notFoundWithLinkIsRejected() {
        assertThatThrownBy(() -> new LinkResolution(new Link(), Outcome.NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link presence");
    }

    @Test
    @DisplayName("a missing outcome is rejected")
    void missingOutcomeIsRejected() {
        assertThatThrownBy(() -> new LinkResolution(new Link(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }
}
