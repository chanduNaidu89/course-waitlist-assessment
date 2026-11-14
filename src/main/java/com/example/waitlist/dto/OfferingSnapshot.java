package com.example.waitlist.dto;

import java.util.List;

/** Point-in-time, consistent view of the offering. Lists are in seat-grant / FIFO order. */
public record OfferingSnapshot(
        String offeringCode,
        int capacity,
        int seatsAvailable,
        List<String> prerequisites,
        List<String> enrolled,
        List<String> waitlist) {
}
