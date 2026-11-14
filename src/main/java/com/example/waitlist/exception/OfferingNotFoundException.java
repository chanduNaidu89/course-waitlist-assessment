package com.example.waitlist.exception;

public class OfferingNotFoundException extends RuntimeException {

    public OfferingNotFoundException(String offeringCode) {
        super("Unknown offering: " + offeringCode);
    }
}
