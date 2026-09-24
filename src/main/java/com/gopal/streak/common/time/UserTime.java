package com.gopal.streak.common.time;

import java.time.ZoneId;
import java.util.Objects;

/**
 * The two settings that decide when a user's day begins and ends.
 *
 * <p>A stand-in for the fields the {@code User} entity will carry, so the timezone maths can be
 * written and tested before any persistence exists.
 *
 * @param zone         IANA zone id, never a fixed offset — offsets change twice a year, zones don't
 * @param deadlineHour local hour at which a day closes, 0–23; 0 means midnight
 */
public record UserTime(ZoneId zone, int deadlineHour) {

    public UserTime {
        Objects.requireNonNull(zone, "zone");
        if (deadlineHour < 0 || deadlineHour > 23) {
            throw new IllegalArgumentException("deadlineHour must be 0-23: " + deadlineHour);
        }
    }

    public static UserTime of(String zoneId, int deadlineHour) {
        return new UserTime(ZoneId.of(zoneId), deadlineHour);
    }
}
