package com.url.shortly.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;

public interface LinkRepository extends JpaRepository<Link, Long> {
    Optional<Link> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
    List<Link> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /** Dashboard list: all of an owner's non-deleted links, optionally one status. */
    Page<Link> findByOwnerIdAndStatusNot(
            Long ownerId, LinkStatus status, Pageable pageable);

    Page<Link> findByOwnerIdAndStatus(
            Long ownerId, LinkStatus status, Pageable pageable);

    /**
     * One aggregate query for the dashboard usage summary.
     *
     * <p>Expired is derived from {@code expiresAt}, not stored as a status, so
     * it is counted separately. Deleted links are excluded from every figure.
     *
     * @return one row with total, active, disabled, blocked, expired,
     *         custom-alias and recently-created counts (in that order)
     */
    @Query("""
            select
                count(l),
                sum(case when l.status = :active
                          and (l.expiresAt is null or l.expiresAt > :now)
                         then 1 else 0 end),
                sum(case when l.status = :disabled then 1 else 0 end),
                sum(case when l.status = :blocked then 1 else 0 end),
                sum(case when l.expiresAt is not null and l.expiresAt <= :now
                         then 1 else 0 end),
                sum(case when l.customAlias = true then 1 else 0 end),
                sum(case when l.createdAt >= :cutoff then 1 else 0 end)
              from Link l
             where l.ownerId = :ownerId
               and l.status <> :deleted
            """)
    List<Object[]> usageSummary(@Param("ownerId") Long ownerId,
                                @Param("active") LinkStatus active,
                                @Param("disabled") LinkStatus disabled,
                                @Param("blocked") LinkStatus blocked,
                                @Param("deleted") LinkStatus deleted,
                                @Param("now") Instant now,
                                @Param("cutoff") Instant cutoff);
}