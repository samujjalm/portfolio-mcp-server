package de.samujjal.java_net.portfolio;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Data access for the trading domain, backed by the auto-configured jOOQ
 * {@link DSLContext}. Uses jOOQ plain-SQL templates (no code generation) so the
 * schema lives entirely in the Flyway migrations.
 */
@Repository
public class PortfolioRepository {

    private final DSLContext dsl;

    public PortfolioRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Customer> findCustomer(long customerId) {
        return dsl.fetchOptional("select id, name, cash_balance from customer where id = ?", customerId)
                .map(r -> new Customer(
                        r.get("id", Long.class),
                        r.get("name", String.class),
                        r.get("cash_balance", BigDecimal.class)));
    }

    public void updateCash(long customerId, BigDecimal newBalance) {
        dsl.execute("update customer set cash_balance = ? where id = ?", newBalance, customerId);
    }

    public Optional<Instrument> findInstrument(String symbol) {
        return dsl.fetchOptional(
                        "select symbol, name, isin, wkn, last_price from instrument where symbol = ?", symbol)
                .map(r -> new Instrument(
                        r.get("symbol", String.class),
                        r.get("name", String.class),
                        r.get("isin", String.class),
                        r.get("wkn", String.class),
                        r.get("last_price", BigDecimal.class)));
    }

    /** Instruments ordered by symbol, keyset-paginated: returns up to {@code limit} rows with symbol > {@code afterSymbol}. */
    public List<Instrument> listInstruments(String afterSymbol, int limit) {
        return dsl.fetch("""
                        select symbol, name, isin, wkn, last_price
                        from instrument
                        where (?::text is null or symbol > ?)
                        order by symbol
                        limit ?
                        """, afterSymbol, afterSymbol, limit)
                .map(r -> new Instrument(
                        r.get("symbol", String.class),
                        r.get("name", String.class),
                        r.get("isin", String.class),
                        r.get("wkn", String.class),
                        r.get("last_price", BigDecimal.class)));
    }

    public void insertLot(long customerId, String symbol, BigDecimal buyPrice, int quantity) {
        dsl.execute("""
                insert into holding_lot (customer_id, symbol, buy_price, original_quantity, remaining_quantity)
                values (?, ?, ?, ?, ?)
                """, customerId, symbol, buyPrice, quantity, quantity);
    }

    /** Open lots for a holding, ordered oldest-first for FIFO consumption. */
    public List<OpenLot> openLotsFifo(long customerId, String symbol) {
        return dsl.fetch("""
                        select id, buy_price, remaining_quantity
                        from holding_lot
                        where customer_id = ? and symbol = ? and remaining_quantity > 0
                        order by acquired_at, id
                        """, customerId, symbol)
                .map(r -> new OpenLot(
                        r.get("id", Long.class),
                        r.get("buy_price", BigDecimal.class),
                        r.get("remaining_quantity", Integer.class)));
    }

    public void updateLotRemaining(long lotId, int remainingQuantity) {
        dsl.execute("update holding_lot set remaining_quantity = ? where id = ?", remainingQuantity, lotId);
    }

    public long insertTrade(long customerId, String symbol, Side side, int quantity,
                            BigDecimal price, BigDecimal realizedPnl) {
        return dsl.fetchSingle("""
                        insert into trade (customer_id, symbol, side, quantity, price, realized_pnl)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """, customerId, symbol, side.name(), quantity, price, realizedPnl)
                .get("id", Long.class);
    }

    /**
     * Trade ledger newest-first, keyset-paginated: returns up to {@code limit} rows with id &lt; {@code beforeId}
     * (pass {@code null} for the first page). Ordering by id desc matches executed-at desc for our inserts.
     */
    public List<TradeRecord> tradeHistory(long customerId, Long beforeId, int limit) {
        return dsl.fetch("""
                        select id, symbol, side, quantity, price, realized_pnl, executed_at
                        from trade
                        where customer_id = ? and (?::bigint is null or id < ?)
                        order by id desc
                        limit ?
                        """, customerId, beforeId, beforeId, limit)
                .map(r -> new TradeRecord(
                        r.get("id", Long.class),
                        r.get("symbol", String.class),
                        Side.valueOf(r.get("side", String.class)),
                        r.get("quantity", Integer.class),
                        r.get("price", BigDecimal.class),
                        r.get("realized_pnl", BigDecimal.class),
                        r.get("executed_at", OffsetDateTime.class)));
    }

    /** All aggregated positions for a customer, derived from open FIFO lots. */
    public List<Position> positions(long customerId) {
        return dsl.fetch("""
                        select hl.symbol,
                               i.name,
                               i.last_price,
                               sum(hl.remaining_quantity)                  as qty,
                               sum(hl.remaining_quantity * hl.buy_price)   as basis
                        from holding_lot hl
                        join instrument i on i.symbol = hl.symbol
                        where hl.customer_id = ? and hl.remaining_quantity > 0
                        group by hl.symbol, i.name, i.last_price
                        order by hl.symbol
                        """, customerId)
                .map(PortfolioRepository::mapPosition);
    }

    /** A single aggregated position, or empty if the customer holds no open shares of it. */
    public Optional<Position> position(long customerId, String symbol) {
        return dsl.fetchOptional("""
                        select hl.symbol,
                               i.name,
                               i.last_price,
                               sum(hl.remaining_quantity)                  as qty,
                               sum(hl.remaining_quantity * hl.buy_price)   as basis
                        from holding_lot hl
                        join instrument i on i.symbol = hl.symbol
                        where hl.customer_id = ? and hl.symbol = ? and hl.remaining_quantity > 0
                        group by hl.symbol, i.name, i.last_price
                        """, customerId, symbol)
                .map(PortfolioRepository::mapPosition);
    }

    private static Position mapPosition(org.jooq.Record r) {
        int quantity = r.get("qty", Integer.class);
        BigDecimal basis = r.get("basis", BigDecimal.class);
        BigDecimal lastPrice = r.get("last_price", BigDecimal.class);
        BigDecimal avgPrice = basis.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
        BigDecimal marketValue = lastPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal unrealizedPnl = marketValue.subtract(basis);
        return new Position(
                r.get("symbol", String.class),
                r.get("name", String.class),
                quantity,
                avgPrice,
                lastPrice,
                marketValue,
                unrealizedPnl);
    }
}
