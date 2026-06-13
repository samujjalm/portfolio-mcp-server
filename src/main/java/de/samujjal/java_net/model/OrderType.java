package de.samujjal.java_net.model;

/** How a trade is priced. MARKET fills at the current price; LIMIT only fills if marketable against a limit price. */
public enum OrderType {
    MARKET,
    LIMIT
}
