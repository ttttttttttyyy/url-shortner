-- V1: initial link schema.
-- owner_id has no foreign key yet; the user table arrives with authentication.

CREATE TABLE link (
    id              BIGSERIAL PRIMARY KEY,
    short_code      VARCHAR(32)   NOT NULL,
    destination_url VARCHAR(2048) NOT NULL,
    owner_id        BIGINT,
    status          VARCHAR(16)   NOT NULL,
    custom_alias    BOOLEAN       NOT NULL DEFAULT FALSE,
    password_hash   VARCHAR(255),
    expires_at      TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at      TIMESTAMP WITH TIME ZONE,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_link_short_code ON link (short_code);
CREATE INDEX idx_link_owner ON link (owner_id);
CREATE INDEX idx_link_expires_at ON link (expires_at);
