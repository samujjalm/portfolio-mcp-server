package de.samujjal.java_net.portfolio;

import java.util.List;

/**
 * A page of results for cursor-based pagination. {@code nextCursor} is an opaque
 * token to pass back as the {@code cursor} argument to fetch the next page, or
 * {@code null} when there are no more results.
 */
public record Page<T>(List<T> items, String nextCursor) {
}
