package de.samujjal.java_net.model;

import java.math.BigDecimal;

/** A trading account holder with available cash. */
public record Customer(long id, String name, BigDecimal cashBalance) {
}
