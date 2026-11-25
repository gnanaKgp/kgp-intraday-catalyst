package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.config.FunnelProperties;
import com.catalyst.terminal.model.funnel.ExecutionPlan;
import com.catalyst.terminal.model.funnel.GuardrailStatus;
import com.catalyst.terminal.model.funnel.TradeSide;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Enforces all 4 hard-coded institutional disqualifiers and calculates 1% risk-per-trade Execution Math:
 * 1. Liquidity Floor (Nifty 500 universe, >= ₹20 Cr turnover, price >= ₹50)
 * 2. Exhaustion Filter (14-day ATR multiple: >2.0x–2.5x soft -10 score penalty; >2.5x hard block)
 * 3. Midday Chop Mute (11:30–13:30 IST breakout suppression unless valid VWAP pullback)
 * 4. Minimum 1:2 Risk-to-Reward Check against technical stop and next resistance/support.
 */
@Service
public class SetupGuardrailService {

    private static final LocalTime MIDDAY_START = LocalTime.of(11, 30);
    private static final LocalTime MIDDAY_END = LocalTime.of(13, 30);

    private final FunnelProperties properties;

    public SetupGuardrailService(FunnelProperties properties) {
        this.properties = properties;
    }

    public GuardrailEvaluation evaluate(
            boolean inLiquidUniverse,
            double avgTurnover20dCr,
            double ltp,
            double prevClose,
            double atr14,
            double vwap,
            double swingLow,
            double swingHigh,
            double pdh,
            double pdl,
            double dayHigh,
            double dayLow,
            double spreadPct,
            TradeSide side,
            LocalTime timeIst
    ) {
        List<String> reasons = new ArrayList<>();

        // 1. Liquidity Floor
        boolean liquidityPassed = inLiquidUniverse
                && avgTurnover20dCr >= properties.getMinTurnoverCr()
                && ltp >= properties.getMinPrice();
        if (!inLiquidUniverse) {
            reasons.add("Outside Nifty 500 liquid universe");
        } else if (avgTurnover20dCr < properties.getMinTurnoverCr()) {
            reasons.add(String.format("Avg turnover ₹%.1f Cr < ₹%.0f Cr floor", avgTurnover20dCr, properties.getMinTurnoverCr()));
        }
        if (ltp < properties.getMinPrice()) {
            reasons.add(String.format("Price ₹%.2f < ₹%.0f floor", ltp, properties.getMinPrice()));
        }

        // 2. Exhaustion Filter (14-day Daily ATR budget)
        double moveFromPrev = Math.abs(ltp - prevClose);
        double atrMultiple = (atr14 > 0) ? round2(moveFromPrev / atr14) : 0.0;
        boolean hardExhausted = atr14 > 0 && atrMultiple > properties.getHardExhaustionAtrMultiple();
        boolean softExhausted = atr14 > 0 && atrMultiple > properties.getMaxAtrMultiple();
        boolean exhaustionPassed = !hardExhausted;
        double atrPenalty = 0.0;
        if (hardExhausted) {
            atrPenalty = 45.0;
            reasons.add(String.format("Exhausted: moved %.2fx 14d ATR (> %.1fx limit)", atrMultiple, properties.getHardExhaustionAtrMultiple()));
        } else if (softExhausted) {
            // Soft penalty is -10 so setups at 2.0-2.5x ATR can still clear the 75-point bar when other factors are strong
            atrPenalty = 10.0;
            reasons.add(String.format("Extended: moved %.2fx 14d ATR (> %.1fx warning, -10 score penalty)", atrMultiple, properties.getMaxAtrMultiple()));
        }

        // 3. Midday Chop Mute (11:30 AM – 1:30 PM IST)
        boolean isMidday = timeIst != null && !timeIst.isBefore(MIDDAY_START) && !timeIst.isAfter(MIDDAY_END);
        boolean vwapPullback = vwap > 0 && (Math.abs(ltp - vwap) / vwap) <= 0.006
                && ((side == TradeSide.LONG && ltp >= vwap * 0.997)
                    || (side == TradeSide.SHORT && ltp <= vwap * 1.003));
        boolean middayChopPassed = !isMidday || vwapPullback;
        if (isMidday && !vwapPullback) {
            reasons.add("Midday Chop Mute (11:30–13:30 IST): non-pullback breakout paused");
        }

        // 4. Execution Math & Minimum 1:2 Risk-to-Reward Check
        ExecutionPlan plan = buildExecutionPlan(side, ltp, vwap, swingLow, swingHigh, pdh, pdl, dayHigh, dayLow);
        boolean rrPassed = plan != null && plan.rrRatio() >= properties.getMinRiskReward() && plan.quantity() > 0;
        if (side == TradeSide.NONE) {
            reasons.add("No directional bias");
        } else if (plan == null || plan.riskPerShare() <= 0) {
            reasons.add("Invalid technical stop-loss distance");
        } else if (plan.rrRatio() < properties.getMinRiskReward()) {
            reasons.add(String.format("R:R %.2f < %.1f minimum to next level", plan.rrRatio(), properties.getMinRiskReward()));
        }

        boolean allPassed = liquidityPassed && exhaustionPassed && middayChopPassed && rrPassed;
        GuardrailStatus status = new GuardrailStatus(
                allPassed,
                liquidityPassed,
                exhaustionPassed,
                middayChopPassed,
                rrPassed,
                isMidday && vwapPullback,
                round2(atr14),
                atrMultiple,
                round2(avgTurnover20dCr),
                round2(spreadPct),
                List.copyOf(reasons)
        );
        return new GuardrailEvaluation(status, plan, atrPenalty);
    }

    ExecutionPlan buildExecutionPlan(
            TradeSide side,
            double ltp,
            double vwap,
            double swingLow,
            double swingHigh,
            double pdh,
            double pdl,
            double dayHigh,
            double dayLow
    ) {
        if (side == TradeSide.NONE || ltp <= 0) {
            return null;
        }
        double riskInr = properties.getCapitalInr() * (properties.getRiskPerTradePct() / 100.0);

        if (side == TradeSide.LONG) {
            double candidateStop = Math.max(
                    vwap > 0 && vwap < ltp ? vwap * 0.998 : 0.0,
                    swingLow > 0 && swingLow < ltp ? swingLow : 0.0
            );
            if (candidateStop <= 0 || candidateStop >= ltp) {
                candidateStop = dayLow > 0 && dayLow < ltp ? dayLow : round2(ltp * 0.99);
            }
            double stopLoss = round2(candidateStop);
            double risk = round2(ltp - stopLoss);
            if (risk <= 0) return null;

            double minTarget = round2(ltp + properties.getMinRiskReward() * risk);
            // Major overhead resistance is Previous Day High (PDH) when above entry; ignore current candle's own intrabar wick
            double overhead = pdh > ltp * 1.001 ? pdh : Double.MAX_VALUE;

            double effectiveTarget = (overhead < Double.MAX_VALUE && overhead >= minTarget)
                    ? round2(overhead)
                    : (overhead < Double.MAX_VALUE ? round2(overhead) : minTarget);
            double reward = round2(effectiveTarget - ltp);
            double rr = round2(reward / risk);
            int qty = (int) Math.floor(riskInr / risk);
            return new ExecutionPlan(TradeSide.LONG, round2(ltp), stopLoss, effectiveTarget, qty, round2(riskInr), risk, reward, rr);
        } else {
            double candidateStop = Double.MAX_VALUE;
            if (vwap > ltp) candidateStop = Math.min(candidateStop, vwap * 1.002);
            if (swingHigh > ltp) candidateStop = Math.min(candidateStop, swingHigh);
            if (candidateStop == Double.MAX_VALUE || candidateStop <= ltp) {
                candidateStop = dayHigh > ltp ? dayHigh : round2(ltp * 1.01);
            }
            double stopLoss = round2(candidateStop);
            double risk = round2(stopLoss - ltp);
            if (risk <= 0) return null;

            double minTarget = round2(ltp - properties.getMinRiskReward() * risk);
            double supportFloor = (pdl > 0 && pdl < ltp * 0.999) ? pdl : 0.0;

            double effectiveTarget = (supportFloor > 0 && supportFloor <= minTarget)
                    ? round2(supportFloor)
                    : (supportFloor > 0 ? round2(supportFloor) : Math.max(0.05, minTarget));
            double reward = round2(ltp - effectiveTarget);
            double rr = round2(reward / risk);
            int qty = (int) Math.floor(riskInr / risk);
            return new ExecutionPlan(TradeSide.SHORT, round2(ltp), stopLoss, effectiveTarget, qty, round2(riskInr), risk, reward, rr);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public record GuardrailEvaluation(GuardrailStatus status, ExecutionPlan executionPlan, double atrPenalty) {}
}

