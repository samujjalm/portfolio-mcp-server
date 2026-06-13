-- A demo customer with starting cash, and a small universe of tradable instruments.
insert into customer (name, cash_balance) values ('Demo Customer', 100000.0000);

insert into instrument (symbol, name, isin, wkn, last_price) values
    ('AAPL', 'Apple Inc.',            'US0378331005', '865985', 195.0000),
    ('MSFT', 'Microsoft Corporation', 'US5949181045', '870747', 430.0000),
    ('NVDA', 'NVIDIA Corporation',    'US67066G1040', '918422', 120.0000),
    ('SAP',  'SAP SE',                'DE0007164600', '716460', 175.0000),
    ('TSLA', 'Tesla, Inc.',           'US88160R1014', 'A1CX3T', 250.0000);
