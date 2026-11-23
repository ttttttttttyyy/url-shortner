-- V2: identity and authentication (FR-05).
--
-- Password reset / email verification is deferred until an email provider is
-- selected, so there is deliberately no user_token table yet.
--
-- Emails are normalized to lowercase by the application before insert, which
-- makes the plain unique constraint equivalent to a lower(email) index while
-- staying portable across PostgreSQL and H2.
--
-- auth_session stores only a SHA-256 hash of the opaque refresh token. The
-- family_id links a rotated token chain so reuse of an already-rotated token
-- can revoke the whole family.

CREATE TABLE app_user (
    id                BIGSERIAL PRIMARY KEY,
    email             VARCHAR(320) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    display_name      VARCHAR(80),
    role              VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    email_verified_at TIMESTAMP WITH TIME ZONE,
    last_login_at     TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_app_user_status ON app_user (status);

CREATE TABLE auth_session (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL,
    family_id          VARCHAR(36) NOT NULL,
    user_agent         VARCHAR(512),
    ip_hash            VARCHAR(64),
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at         TIMESTAMP WITH TIME ZONE,
    last_used_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ux_auth_session_token UNIQUE (refresh_token_hash)
);

CREATE INDEX idx_auth_session_user ON auth_session (user_id, expires_at);
CREATE INDEX idx_auth_session_expiry ON auth_session (expires_at);
CREATE INDEX idx_auth_session_family ON auth_session (family_id);

-- Existing V1 rows can only be guest links: no user table existed before this
-- migration, so a non-null owner_id would already be inconsistent. The
-- migration fails loudly rather than silently discarding that data.
ALTER TABLE link
    ADD CONSTRAINT fk_link_owner FOREIGN KEY (owner_id)
    REFERENCES app_user (id) ON DELETE SET NULL;
