package com.url.shortly.enums;

/**
 * Account role used for authorization decisions.
 *
 * <p>Registration always creates {@link #USER}. Administrative roles are
 * introduced with FR-16; keeping the enum small now avoids granting a role
 * that no endpoint checks.
 */
public enum UserRole {
    USER,
    ADMIN
}
