package com.url.shortly.service.imp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.url.shortly.Entities.ClickEvent;
import com.url.shortly.Repository.ClickEventRepository;
import com.url.shortly.service.AdvancedAnalyticsService;
import com.url.shortly.service.LinkAdvancedAnalytics;

/**
 * FR-10: aggregates the dimensions captured at record time.
 *
 * <p>The same range query as FR-09 feeds all breakdowns, so totals across a
 * dimension reconcile with the basic click total. Lists are capped to keep the
 * response bounded; the remainder is grouped as {@code Other}.
 */
@Service
public class AdvancedAnalyticsServiceImp implements AdvancedAnalyticsService {

    private static final int MAX_ENTRIES = 20;
    private static final String OTHER = "Other";
    private static final String DIRECT = "Direct";

    private final ClickEventRepository clicks;
    private final Clock clock;

    public AdvancedAnalyticsServiceImp(ClickEventRepository clicks, Clock clock) {
        this.clicks = clicks;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public LinkAdvancedAnalytics analytics(Long linkId, int days) {
        Instant since = clock.instant().minus(Duration.ofDays(days));
        List<ClickEvent> events =
                clicks.findByLinkIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(linkId, since);

        Map<String, Long> countries = new HashMap<>();
        Map<String, Long> devices = new HashMap<>();
        Map<String, Long> operatingSystems = new HashMap<>();
        Map<String, Long> browsers = new HashMap<>();
        Map<String, Long> referrers = new HashMap<>();

        for (ClickEvent event : events) {
            add(countries, event.getCountry());
            add(devices, event.getDeviceType());
            add(operatingSystems, event.getOperatingSystem());
            add(browsers, event.getBrowser());
            add(referrers, event.getReferrer() == null ? DIRECT : event.getReferrer());
        }

        return new LinkAdvancedAnalytics(
                linkId,
                days,
                breakdown(countries),
                breakdown(devices),
                breakdown(operatingSystems),
                breakdown(browsers),
                breakdown(referrers));
    }

    private static void add(Map<String, Long> counts, String value) {
        if (value != null && !value.isBlank()) {
            counts.merge(value, 1L, Long::sum);
        }
    }

    private static List<LinkAdvancedAnalytics.Breakdown> breakdown(Map<String, Long> counts) {
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));

        List<LinkAdvancedAnalytics.Breakdown> result = new ArrayList<>();
        long remainder = 0L;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            if (i < MAX_ENTRIES) {
                result.add(new LinkAdvancedAnalytics.Breakdown(entry.getKey(), entry.getValue()));
            } else {
                remainder += entry.getValue();
            }
        }
        if (remainder > 0) {
            result.add(new LinkAdvancedAnalytics.Breakdown(OTHER, remainder));
        }
        return List.copyOf(result);
    }
}
