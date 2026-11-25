package com.catalyst.terminal.gateway;

import com.catalyst.terminal.model.GeminiCatalystResult;
import com.catalyst.terminal.model.MarketSnapshot;

public interface LlmGateway {
    GeminiCatalystResult evaluateFiling(String symbol, String companyName, String filingText, MarketSnapshot market);
}

