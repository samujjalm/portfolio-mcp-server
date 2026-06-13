package de.samujjal.java_net.repository;

import de.samujjal.java_net.TestcontainersConfiguration;
import de.samujjal.java_net.model.Customer;
import de.samujjal.java_net.model.Instrument;
import de.samujjal.java_net.model.OpenLot;
import de.samujjal.java_net.model.Position;
import de.samujjal.java_net.model.Side;
import de.samujjal.java_net.model.TradeRecord;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the jOOQ {@link PortfolioRepository} against a real Postgres
 * (Testcontainers). Verifies the SQL and row→record mapping directly — FIFO lot
 * ordering, position aggregation, the trade ledger, and instrument identifiers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PortfolioRepositoryTest {

    private static final long DEMO_CUSTOMER = 1L;

    @Autowired
    PortfolioRepository repository;

    @Autowired
    DSLContext dsl;

    @Test
    void findsSeededCustomer() {
        Optional<Customer> customer = repository.findCustomer(DEMO_CUSTOMER);
        assertThat(customer).isPresent();
        assertThat(customer.get().name()).isEqualTo("Demo Customer");
        assertThat(customer.get().cashBalance()).isEqualByComparingTo("100000.0000");
    }

    @Test
    void returnsEmptyForUnknownCustomer() {
        assertThat(repository.findCustomer(9_999L)).isEmpty();
    }

    @Test
    void mapsInstrumentIsinAndWkn() {
        Instrument sap = repository.findInstrument("SAP").orElseThrow();
        assertThat(sap.isin()).isEqualTo("DE0007164600");
        assertThat(sap.wkn()).isEqualTo("716460");
        assertThat(sap.lastPrice()).isEqualByComparingTo("175.0000");
    }

    @Test
    void mapsInstrumentWithNullWkn() {
        // wkn is nullable; verify the mapping handles a null without blowing up.
        dsl.execute("""
                insert into instrument (symbol, name, isin, wkn, last_price)
                values ('NOWKN', 'No WKN Corp', 'US0000000000', null, 50.0000)
                """);
        Instrument noWkn = repository.findInstrument("NOWKN").orElseThrow();
        assertThat(noWkn.wkn()).isNull();
        assertThat(noWkn.isin()).isEqualTo("US0000000000");
    }

    @Test
    void openLotsAreReturnedOldestFirstAndExcludeEmptyLots() {
        repository.insertLot(DEMO_CUSTOMER, "AAPL", new BigDecimal("195.0000"), 10); // lot 1
        repository.insertLot(DEMO_CUSTOMER, "AAPL", new BigDecimal("205.0000"), 5);  // lot 2

        List<OpenLot> lots = repository.openLotsFifo(DEMO_CUSTOMER, "AAPL");
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0).buyPrice()).isEqualByComparingTo("195.0000"); // oldest first
        assertThat(lots.get(1).buyPrice()).isEqualByComparingTo("205.0000");

        // Exhaust lot 1; it should drop out of the FIFO list.
        repository.updateLotRemaining(lots.get(0).id(), 0);
        List<OpenLot> remaining = repository.openLotsFifo(DEMO_CUSTOMER, "AAPL");
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).buyPrice()).isEqualByComparingTo("205.0000");
    }

    @Test
    void positionAggregatesWeightedAveragePrice() {
        repository.insertLot(DEMO_CUSTOMER, "MSFT", new BigDecimal("400.0000"), 10);
        repository.insertLot(DEMO_CUSTOMER, "MSFT", new BigDecimal("420.0000"), 10);

        Position position = repository.position(DEMO_CUSTOMER, "MSFT").orElseThrow();
        assertThat(position.quantity()).isEqualTo(20);
        // (10*400 + 10*420) / 20 = 410
        assertThat(position.averagePrice()).isEqualByComparingTo("410.0000");
        assertThat(position.name()).isEqualTo("Microsoft Corporation");
    }

    @Test
    void insertTradeReturnsIdAndAppearsInHistory() {
        long tradeId = repository.insertTrade(
                DEMO_CUSTOMER, "NVDA", Side.BUY, 4, new BigDecimal("120.0000"), null);
        assertThat(tradeId).isPositive();

        List<TradeRecord> history = repository.tradeHistory(DEMO_CUSTOMER, null, 10);
        assertThat(history).hasSize(1);
        TradeRecord record = history.get(0);
        assertThat(record.id()).isEqualTo(tradeId);
        assertThat(record.side()).isEqualTo(Side.BUY);
        assertThat(record.quantity()).isEqualTo(4);
        assertThat(record.executedAt()).isNotNull();
    }

    @Test
    void updateCashPersists() {
        repository.updateCash(DEMO_CUSTOMER, new BigDecimal("12345.6700"));
        assertThat(repository.findCustomer(DEMO_CUSTOMER).orElseThrow().cashBalance())
                .isEqualByComparingTo("12345.6700");
    }

    @Test
    void listInstrumentsKeysetPaginatesBySymbol() {
        // First two of the five seeded instruments (AAPL, MSFT, NVDA, SAP, TSLA).
        List<Instrument> firstTwo = repository.listInstruments(null, 2);
        assertThat(firstTwo).extracting(Instrument::symbol).containsExactly("AAPL", "MSFT");

        List<Instrument> afterMsft = repository.listInstruments("MSFT", 2);
        assertThat(afterMsft).extracting(Instrument::symbol).containsExactly("NVDA", "SAP");
    }

    @Test
    void tradeHistoryKeysetPaginatesByIdDescending() {
        long t1 = repository.insertTrade(DEMO_CUSTOMER, "AAPL", Side.BUY, 1, new BigDecimal("195.0000"), null);
        long t2 = repository.insertTrade(DEMO_CUSTOMER, "MSFT", Side.BUY, 1, new BigDecimal("430.0000"), null);

        List<TradeRecord> firstPage = repository.tradeHistory(DEMO_CUSTOMER, null, 1);
        assertThat(firstPage).extracting(TradeRecord::id).containsExactly(t2); // newest first

        List<TradeRecord> nextPage = repository.tradeHistory(DEMO_CUSTOMER, t2, 1);
        assertThat(nextPage).extracting(TradeRecord::id).containsExactly(t1);
    }
}
