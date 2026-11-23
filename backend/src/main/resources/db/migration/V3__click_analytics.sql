-- V3: basic click analytics (FR-09).
--
-- Only eligible redirects are recorded. The visitor identity is a salted
-- hash produced by ClientIpResolver; raw addresses are never stored.
-- Referrer/user-agent columns are captured now so FR-10 can expose breakdowns
-- without losing history, but they are not part of the FR-09 response.

CREATE TABLE click_event (
    id           BIGSERIAL PRIMARY KEY,
    link_id      BIGINT NOT NULL,
    occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    visitor_hash VARCHAR(64) NOT NULL,
    referrer     VARCHAR(512),
    user_agent   VARCHAR(512),
    CONSTRAINT fk_click_event_link FOREIGN KEY (link_id)
        REFERENCES link (id) ON DELETE CASCADE
);

CREATE INDEX idx_click_event_link_time ON click_event (link_id, occurred_at);
CREATE INDEX idx_click_event_visitor ON click_event (link_id, visitor_hash, occurred_at);
