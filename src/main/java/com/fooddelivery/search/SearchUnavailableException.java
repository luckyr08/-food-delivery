package com.fooddelivery.search;

/** The search cluster can't be reached (in the simulator: availability switched off). */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String message) {
        super(message);
    }
}
