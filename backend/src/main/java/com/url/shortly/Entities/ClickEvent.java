package com.url.shortly.Entities;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One eligible visit to a short link (FR-09).
 *
 * <p>The visitor is identified by a salted hash, never a raw address. Referrer
 * and user-agent are captured for FR-10 but are not exposed by the basic
 * analytics response.
 */
@Entity
// Indexes are declared only by V3__click_analytics.sql: with ddl-auto: update
// also active, repeating them here would make Hibernate and Flyway fight over
// the same index names.
@Table(name = "click_event")
@Getter
@Setter
@NoArgsConstructor
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "visitor_hash", nullable = false, length = 64)
    private String visitorHash;

    /** Referring host only, never a full URL with query strings. */
    @Column(length = 512)
    private String referrer;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(length = 64)
    private String country;

    @Column(name = "device_type", length = 32)
    private String deviceType;

    @Column(name = "operating_system", length = 32)
    private String operatingSystem;

    @Column(length = 32)
    private String browser;
}
