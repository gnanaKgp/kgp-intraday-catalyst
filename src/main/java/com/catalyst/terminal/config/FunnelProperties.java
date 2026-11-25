package com.catalyst.terminal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalyst.funnel")
public class FunnelProperties {

    private boolean enabled = true;
    private long rankIntervalMs = 60000;
    private double minCompositeScore = 75.0;
    private int maxRankedSetups = 10;
    private double minTurnoverCr = 20.0;
    private double minPrice = 50.0;
    private double maxAtrMultiple = 2.0;
    private double hardExhaustionAtrMultiple = 2.5;
    private double minRiskReward = 2.0;
    private double capitalInr = 100000.0;
    private double riskPerTradePct = 1.0;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getRankIntervalMs() { return rankIntervalMs; }
    public void setRankIntervalMs(long rankIntervalMs) { this.rankIntervalMs = rankIntervalMs; }
    public double getMinCompositeScore() { return minCompositeScore; }
    public void setMinCompositeScore(double minCompositeScore) { this.minCompositeScore = minCompositeScore; }
    public int getMaxRankedSetups() { return maxRankedSetups; }
    public void setMaxRankedSetups(int maxRankedSetups) { this.maxRankedSetups = maxRankedSetups; }
    public double getMinTurnoverCr() { return minTurnoverCr; }
    public void setMinTurnoverCr(double minTurnoverCr) { this.minTurnoverCr = minTurnoverCr; }
    public double getMinPrice() { return minPrice; }
    public void setMinPrice(double minPrice) { this.minPrice = minPrice; }
    public double getMaxAtrMultiple() { return maxAtrMultiple; }
    public void setMaxAtrMultiple(double maxAtrMultiple) { this.maxAtrMultiple = maxAtrMultiple; }
    public double getHardExhaustionAtrMultiple() { return hardExhaustionAtrMultiple; }
    public void setHardExhaustionAtrMultiple(double hardExhaustionAtrMultiple) { this.hardExhaustionAtrMultiple = hardExhaustionAtrMultiple; }
    public double getMinRiskReward() { return minRiskReward; }
    public void setMinRiskReward(double minRiskReward) { this.minRiskReward = minRiskReward; }
    public double getCapitalInr() { return capitalInr; }
    public void setCapitalInr(double capitalInr) { this.capitalInr = capitalInr; }
    public double getRiskPerTradePct() { return riskPerTradePct; }
    public void setRiskPerTradePct(double riskPerTradePct) { this.riskPerTradePct = riskPerTradePct; }
}

