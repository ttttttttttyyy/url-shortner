package com.url.shortly.enums;

/**
 * Account lifecycle state. Only {@link #ACTIVE} accounts can authenticate or
 * refresh a session; every other state is treated as unavailable.
 */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
