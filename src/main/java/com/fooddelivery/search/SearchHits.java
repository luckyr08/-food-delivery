package com.fooddelivery.search;

import java.util.List;

public record SearchHits(List<SearchHit> hits, long total) {
}
