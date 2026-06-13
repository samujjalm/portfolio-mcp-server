package de.samujjal.java_net.portfolio;

import de.samujjal.java_net.TestcontainersConfiguration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies FIFO lot consumption, realised P&L, cash movements and guard rails. */
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

    @Test
    void fifoSellConsumesOldestLotsAndComputesRealizedPnl() {
        // Two buys at different prices build a FIFO chain.
        setPrice("AAPL", "195.00");
        service.executeTrade(DEMO_CUSTOMER, "AAPL", Side.BUY, 10);   // lot 1: 10 @ 195
        setPrice("AAPL", "205.00");
        service.executeTrade(DEMO_CUSTOMER, "AAPL", Side.BUY, 5);    // lot 2: 5 @ 205

        PortfolioView afterBuys = service.getPortfolio(DEMO_CUSTOMER);
        Position aapl = afterBuys.positions().stream()
                .filter(p -> p.symbol().equals("AAPL")).findFirst().orElseThrow();
        assertThat(aapl.quantity()).isEqualTo(15);
        // weighted avg = (10*195 + 5*205) / 15 = 198.3333
        assertThat(aapl.averagePrice()).isEqualByComparingTo("198.3333");
        // cash spent = 1950 + 1025 = 2975
        assertThat(afterBuys.cashBalance()).isEqualByComparingTo("97025.00");

        // Sell 12 @ 210 -> consumes 10 from lot1 (@195) then 2 from lot2 (@205).
        setPrice("AAPL", "210.00");
        TradeResult sell = service.executeTrade(DEMO_CUSTOMER, "AAPL", Side.SELL, 12);

        // cost basis = 10*195 + 2*205 = 2360; proceeds = 12*210 = 2520; pnl = 160
        assertThat(sell.realizedPnl()).isEqualByComparingTo("160.00");
        assertThat(sell.cashBalance()).isEqualByComparingTo("99545.00"); // 97025 + 2520
        // remaining: 3 shares from lot2 @ 205
        assertThat(sell.resultingPosition()).isNotNull();
        assertThat(sell.resultingPosition().quantity()).isEqualTo(3);
        assertThat(sell.resultingPosition().averagePrice()).isEqualByComparingTo("205.0000");
    }

    @Test
    void sellingEntirePositionClosesItOut() {
        setPrice("NVDA", "120.00");
        service.executeTrade(DEMO_CUSTOMER, "NVDA", Side.BUY, 4);
        TradeResult sell = service.executeTrade(DEMO_CUSTOMER, "NVDA", Side.SELL, 4);

        assertThat(sell.resultingPosition()).isNull();
        assertThat(service.getPortfolio(DEMO_CUSTOMER).positions())
                .noneMatch(p -> p.symbol().equals("NVDA"));
    }

    @Test
    void rejectsSellWithoutEnoughShares() {
        assertThatThrownBy(() -> service.executeTrade(DEMO_CUSTOMER, "MSFT", Side.SELL, 1))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Insufficient shares");
    }

    @Test
    void rejectsBuyBeyondCash() {
        setPrice("MSFT", "430.00");
        assertThatThrownBy(() -> service.executeTrade(DEMO_CUSTOMER, "MSFT", Side.BUY, 1_000_000))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void rejectsUnknownSymbol() {
        assertThatThrownBy(() -> service.executeTrade(DEMO_CUSTOMER, "NOPE", Side.BUY, 1))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Unknown instrument");
    }

    @Test
    void listsSeededInstrumentsWithIsinAndWkn() {
        List<Instrument> instruments = service.listInstruments();
        Instrument sap = instruments.stream()
                .filter(i -> i.symbol().equals("SAP")).findFirst().orElseThrow();
        assertThat(sap.isin()).isEqualTo("DE0007164600");
        assertThat(sap.wkn()).isEqualTo("716460");
    }
}
