package de.samujjal.java_net.service;

import de.samujjal.java_net.TestcontainersConfiguration;
import de.samujjal.java_net.model.Instrument;
import de.samujjal.java_net.model.OrderType;
import de.samujjal.java_net.model.Page;
import de.samujjal.java_net.model.PortfolioView;
import de.samujjal.java_net.model.Position;
import de.samujjal.java_net.model.Quote;
import de.samujjal.java_net.model.Side;
import de.samujjal.java_net.model.TradeException;
import de.samujjal.java_net.model.TradePreview;
import de.samujjal.java_net.model.TradeRequest;
import de.samujjal.java_net.model.TradeResult;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies FIFO accounting, idempotency, limit orders, simulation, pagination and quote freshness. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PortfolioServiceTest {

    private static final long DEMO_CUSTOMER = 1L;

    @Autowired
    PortfolioService service;

    @Autowired
    DSLContext dsl;

    private void setPrice(String symbol, String price) {
        dsl.execute("update instrument set last_price = ? where symbol = ?", new BigDecimal(price), symbol);
    }

    /** A MARKET order with a fresh idempotency key. */
    private static TradeRequest market(long customerId, String symbol, Side side, int quantity) {
        return new TradeRequest(customerId, symbol, side, quantity,
                UUID.randomUUID().toString(), OrderType.MARKET, null);
    }

    private static TradeRequest limit(long customerId, String symbol, Side side, int quantity, String limitPrice) {
        return new TradeRequest(customerId, symbol, side, quantity,
                UUID.randomUUID().toString(), OrderType.LIMIT, limitPrice == null ? null : new BigDecimal(limitPrice));
    }

    @Test
    void fifoSellConsumesOldestLotsAndComputesRealizedPnl() {
        setPrice("AAPL", "195.00");
        service.executeTrade(market(DEMO_CUSTOMER, "AAPL", Side.BUY, 10));   // lot 1: 10 @ 195
        setPrice("AAPL", "205.00");
        service.executeTrade(market(DEMO_CUSTOMER, "AAPL", Side.BUY, 5));    // lot 2: 5 @ 205

        PortfolioView afterBuys = service.getPortfolio(DEMO_CUSTOMER);
        Position aapl = afterBuys.positions().stream()
                .filter(p -> p.symbol().equals("AAPL")).findFirst().orElseThrow();
        assertThat(aapl.quantity()).isEqualTo(15);
        assertThat(aapl.averagePrice()).isEqualByComparingTo("198.3333");
        assertThat(afterBuys.cashBalance()).isEqualByComparingTo("97025.00");

        setPrice("AAPL", "210.00");
        TradeResult sell = service.executeTrade(market(DEMO_CUSTOMER, "AAPL", Side.SELL, 12));

        assertThat(sell.realizedPnl()).isEqualByComparingTo("160.00");   // 2520 proceeds - 2360 basis
        assertThat(sell.cashBalance()).isEqualByComparingTo("99545.00");
        assertThat(sell.resultingPosition()).isNotNull();
        assertThat(sell.resultingPosition().quantity()).isEqualTo(3);
        assertThat(sell.resultingPosition().averagePrice()).isEqualByComparingTo("205.0000");
    }

    @Test
    void sellingEntirePositionClosesItOut() {
        setPrice("NVDA", "120.00");
        service.executeTrade(market(DEMO_CUSTOMER, "NVDA", Side.BUY, 4));
        TradeResult sell = service.executeTrade(market(DEMO_CUSTOMER, "NVDA", Side.SELL, 4));

        assertThat(sell.resultingPosition()).isNull();
        assertThat(service.getPortfolio(DEMO_CUSTOMER).positions())
                .noneMatch(p -> p.symbol().equals("NVDA"));
    }

    @Test
    void rejectsSellWithoutEnoughShares() {
        assertThatThrownBy(() -> service.executeTrade(market(DEMO_CUSTOMER, "MSFT", Side.SELL, 1)))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Insufficient shares");
    }

    @Test
    void rejectsBuyBeyondCash() {
        setPrice("MSFT", "430.00");
        assertThatThrownBy(() -> service.executeTrade(market(DEMO_CUSTOMER, "MSFT", Side.BUY, 1_000_000)))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void rejectsUnknownSymbol() {
        assertThatThrownBy(() -> service.executeTrade(market(DEMO_CUSTOMER, "NOPE", Side.BUY, 1)))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Unknown instrument");
    }

    // --- Task A: idempotency + limit orders ---

    @Test
    void replayingIdempotencyKeyReturnsCachedReceiptWithoutDoubleExecuting() {
        TradeRequest request = market(DEMO_CUSTOMER, "AAPL", Side.BUY, 5); // seed price 195 -> 975
        TradeResult first = service.executeTrade(request);
        TradeResult replay = service.executeTrade(request); // same key

        assertThat(replay.tradeId()).isEqualTo(first.tradeId());
        // Cash debited exactly once.
        assertThat(service.getPortfolio(DEMO_CUSTOMER).cashBalance()).isEqualByComparingTo("99025.00");
    }

    @Test
    void limitBuyFillsAtMarketWhenMarketable() {
        setPrice("AAPL", "100.00");
        TradeResult result = service.executeTrade(limit(DEMO_CUSTOMER, "AAPL", Side.BUY, 5, "150.00"));
        assertThat(result.price()).isEqualByComparingTo("100.0000"); // price improvement honoured
    }

    @Test
    void limitBuyRejectedWhenNotMarketable() {
        setPrice("AAPL", "200.00");
        assertThatThrownBy(() -> service.executeTrade(limit(DEMO_CUSTOMER, "AAPL", Side.BUY, 5, "150.00")))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("not marketable");
    }

    @Test
    void limitOrderRequiresLimitPrice() {
        assertThatThrownBy(() -> service.executeTrade(limit(DEMO_CUSTOMER, "AAPL", Side.BUY, 5, null)))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("limitPrice is required");
    }

    // --- Task B: previewTrade ---

    @Test
    void previewProjectsOutcomeWithoutPersisting() {
        TradePreview preview = service.previewTrade(market(DEMO_CUSTOMER, "AAPL", Side.BUY, 10)); // seed 195

        assertThat(preview.isValid()).isTrue();
        assertThat(preview.estimatedPrice()).isEqualByComparingTo("195.0000");
        assertThat(preview.estimatedCost()).isEqualByComparingTo("1950.0000");
        assertThat(preview.projectedCashBalance()).isEqualByComparingTo("98050.0000");
        assertThat(preview.projectedPosition().quantity()).isEqualTo(10);

        // Nothing was persisted: portfolio is untouched.
        PortfolioView portfolio = service.getPortfolio(DEMO_CUSTOMER);
        assertThat(portfolio.cashBalance()).isEqualByComparingTo("100000.0000");
        assertThat(portfolio.positions()).noneMatch(p -> p.symbol().equals("AAPL"));
    }

    @Test
    void previewReturnsInvalidWithReasonInsteadOfThrowing() {
        TradePreview preview = service.previewTrade(market(DEMO_CUSTOMER, "MSFT", Side.SELL, 1));
        assertThat(preview.isValid()).isFalse();
        assertThat(preview.reason()).contains("Insufficient shares");
    }

    // --- Task C: pagination ---

    @Test
    void listInstrumentsPaginatesWithCursor() {
        Page<Instrument> first = service.listInstruments(2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        Page<Instrument> second = service.listInstruments(2, first.nextCursor());
        assertThat(second.items()).hasSize(2);
        // Keyset advances: the second page starts strictly after the first page's last symbol.
        assertThat(second.items().get(0).symbol())
                .isGreaterThan(first.items().get(1).symbol());
    }

    @Test
    void listInstrumentsExposesIsinAndWkn() {
        Instrument sap = service.listInstruments(100, null).items().stream()
                .filter(i -> i.symbol().equals("SAP")).findFirst().orElseThrow();
        assertThat(sap.isin()).isEqualTo("DE0007164600");
        assertThat(sap.wkn()).isEqualTo("716460");
    }

    // --- Task D: quote freshness ---

    @Test
    void quoteCarriesParseableIsoTimestamp() {
        Quote quote = service.getQuote("AAPL");
        assertThat(quote.lastPrice()).isEqualByComparingTo("195.0000");
        assertThat(Instant.parse(quote.timestamp())).isNotNull(); // ISO-8601 UTC
    }
}
