# Portfolio MCP Server — Tool Reference

> Generated from the `@Tool` annotations by `ToolDocGeneratorTest`. Do not edit by hand — run `./gradlew generateToolDocs` to refresh.

- **Server:** `portfolio-mcp` v0.0.1
- **Transport:** HTTP/SSE — connect at `http://localhost:8080/sse`
- **Tools:** 5

| Tool | Summary |
|------|---------|
| [`executeTrade`](#executetrade) | Execute a trade for a customer at the instrument's current price and update their portfolio. |
| [`getPortfolio`](#getportfolio) | Get a customer's full portfolio: cash balance, every stock position (quantity, average cost, current price, market value, unrealised P&L) and total account value. |
| [`getQuote`](#getquote) | Get the current quote and identifiers (ISIN, WKN) for a single instrument by its ticker symbol. |
| [`getTradeHistory`](#gettradehistory) | Get a customer's recent trade history (most recent first), including realised P&L on sells. |
| [`listInstruments`](#listinstruments) | List all tradable instruments with their symbol, name, ISIN, WKN and current price. |

## `executeTrade`

Execute a trade for a customer at the instrument's current price and update their portfolio. BUY appends a new FIFO lot and debits cash; SELL consumes the oldest lots first, credits cash and reports realised P&L. Fails on unknown customer/symbol, insufficient funds, or insufficient shares.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `request` | object | yes | — | The trade to execute |
| `request.customerId` | integer | yes | min 1, format int64 | The customer's numeric id |
| `request.quantity` | integer | yes | min 1, format int32 | Number of shares to trade |
| `request.side` | string (enum: BUY, SELL) | yes | — | Trade direction |
| `request.symbol` | string | yes | pattern `^[A-Z]{1,6}$` | Ticker symbol to trade, e.g. AAPL |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "request" : {
      "type" : "object",
      "properties" : {
        "customerId" : {
          "type" : "integer",
          "format" : "int64",
          "description" : "The customer's numeric id",
          "minimum" : 1
        },
        "quantity" : {
          "type" : "integer",
          "format" : "int32",
          "description" : "Number of shares to trade",
          "minimum" : 1
        },
        "side" : {
          "type" : "string",
          "enum" : [ "BUY", "SELL" ],
          "description" : "Trade direction"
        },
        "symbol" : {
          "type" : "string",
          "description" : "Ticker symbol to trade, e.g. AAPL",
          "pattern" : "^[A-Z]{1,6}$"
        }
      },
      "required" : [ "customerId", "quantity", "side", "symbol" ],
      "description" : "The trade to execute",
      "additionalProperties" : false
    }
  },
  "required" : [ "request" ],
  "additionalProperties" : false
}
```

</details>

## `getPortfolio`

Get a customer's full portfolio: cash balance, every stock position (quantity, average cost, current price, market value, unrealised P&L) and total account value.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `customerId` | integer | yes | — | The customer's numeric id |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "customerId" : {
      "type" : "integer",
      "description" : "The customer's numeric id"
    }
  },
  "required" : [ "customerId" ],
  "additionalProperties" : false
}
```

</details>

## `getQuote`

Get the current quote and identifiers (ISIN, WKN) for a single instrument by its ticker symbol.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `symbol` | string | yes | — | Ticker symbol — 1–6 uppercase letters, e.g. AAPL |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "symbol" : {
      "type" : "string",
      "description" : "Ticker symbol — 1–6 uppercase letters, e.g. AAPL"
    }
  },
  "required" : [ "symbol" ],
  "additionalProperties" : false
}
```

</details>

## `getTradeHistory`

Get a customer's recent trade history (most recent first), including realised P&L on sells.

**Parameters**

| Name | Type | Required | Constraints | Description |
|------|------|----------|-------------|-------------|
| `customerId` | integer | yes | — | The customer's numeric id |

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : {
    "customerId" : {
      "type" : "integer",
      "description" : "The customer's numeric id"
    }
  },
  "required" : [ "customerId" ],
  "additionalProperties" : false
}
```

</details>

## `listInstruments`

List all tradable instruments with their symbol, name, ISIN, WKN and current price.

_No parameters._

<details><summary>JSON Schema</summary>

```json
{
  "$schema" : "https://json-schema.org/draft/2020-12/schema",
  "type" : "object",
  "properties" : { },
  "required" : [ ],
  "additionalProperties" : false
}
```

</details>

