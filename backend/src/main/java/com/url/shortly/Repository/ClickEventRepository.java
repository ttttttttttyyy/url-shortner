package com.url.shortly.Repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.url.shortly.Entities.ClickEvent;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /** Events in the reporting range, oldest first for unique-visit counting. */
    List<ClickEvent> findByLinkIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
            Long linkId, Instant since);

    long countByLinkId(Long linkId);
}
