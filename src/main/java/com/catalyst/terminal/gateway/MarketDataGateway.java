package com.catalyst.terminal.gateway;

import com.catalyst.terminal.model.MarketSnapshot;

public interface MarketDataGateway {
    MarketSnapshot fetchSnapshot(String symbol);
}

