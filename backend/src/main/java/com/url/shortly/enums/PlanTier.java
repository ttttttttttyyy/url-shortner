package com.url.shortly.enums;

/**
 * Caller tier used to choose the minimum generated-code length.
 *
 * <p>Maps to the SoW roles: guests create links without an account (FR-04),
 * free registered users own a dashboard (FR-06), and paid users hold premium
 * entitlements such as custom aliases (FR-08 / FR-15).
 */
public enum PlanTier {
    GUEST,
    FREE,
    PAID
}
