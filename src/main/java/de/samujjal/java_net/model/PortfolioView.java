package de.samujjal.java_net.model;

import java.math.BigDecimal;
import java.util.List;

/** A full snapshot of a customer's account: cash, positions, and totals. */
public record PortfolioView(
        long customerId,
        String customerName,
        BigDecimal cashBalance,
        List<Position> positions,
        BigDecimal positionsValue,
        BigDecimal totalValue) {
}
