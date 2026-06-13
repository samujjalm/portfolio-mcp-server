package de.samujjal.java_net.model;

import java.math.BigDecimal;

/**
 * A tradable security. {@code isin} is always present and unique; {@code wkn}
 * (German Wertpapierkennnummer) is optional and may be {@code null}.
 */
public record Instrument(
        String symbol,
        String name,
        String isin,
        String wkn,
        BigDecimal lastPrice) {
}
