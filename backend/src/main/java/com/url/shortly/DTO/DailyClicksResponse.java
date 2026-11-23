package com.url.shortly.DTO;

import java.time.Instant;

import com.url.shortly.service.LinkAnalytics.DailyClicks;

/** One UTC day of the analytics trend. */
public record DailyClicksResponse(Instant day, long clicks, long uniqueClicks) {

    public static DailyClicksResponse from(DailyClicks bucket) {
        return new DailyClicksResponse(bucket.day(), bucket.clicks(), bucket.uniqueClicks());
    }
}
