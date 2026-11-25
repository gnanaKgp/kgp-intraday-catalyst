package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.model.funnel.ScoreBreakdown;
import com.catalyst.terminal.model.funnel.TradeSide;
import org.springframework.stereotype.Service;

/**
 * Computes the 5-factor Composite "Profit Probability" Score (0–100):
 * Total = 0.25*Catalyst + 0.25*RVOL + 0.25*Structure + 0.15*RS + 0.10*Liquidity - ATR_Penalty.
 */
@Service
public class CompositeScorerService {

    public ScoreBreakdown score(
            int catalystScore,
            double rvol,
            TradeSide side,
            double ltp,
            double vwap,
            double orh,
            double orl,
            double pdh,
            double pdl,
            double rangePosition,
            boolean risingVolume,
            double stock5mReturnPct,
            double nifty5mReturnPct,
            double sector5mReturnPct,
            double avgTurnover20dCr,
            double spreadPct,
            double atrPenalty
    ) {
        double catNorm = Math.max(0.0, Math.min(100.0, catalystScore));

        double rvolScore;
        if (rvol >= 3.0) rvolScore = 100.0;
        else if (rvol >= 2.0) rvolScore = 80.0;
        else if (rvol >= 1.5) rvolScore = 60.0;
        else if (rvol >= 1.0) rvolScore = 35.0;
        else rvolScore = Math.max(0.0, rvol * 35.0);

        double structureScore = computeStructureScore(side, ltp, vwap, orh, orl, pdh, pdl, rangePosition, risingVolume);
        double rsScore = computeRsScore(side, stock5mReturnPct, nifty5mReturnPct, sector5mReturnPct);
        double liqScore = computeLiquidityScore(avgTurnover20dCr, spreadPct);

        double rawComposite = (0.25 * catNorm)
                + (0.25 * rvolScore)
                + (0.25 * structureScore)
                + (0.15 * rsScore)
                + (0.10 * liqScore)
                - atrPenalty;

        double total = round2(Math.max(0.0, Math.min(100.0, rawComposite)));
        return new ScoreBreakdown(
                round2(catNorm),
                round2(rvolScore),
                round2(structureScore),
                round2(rsScore),
                round2(liqScore),
                total
        );
    }

    private double computeStructureScore(
            TradeSide side,
            double ltp,
            double vwap,
            double orh,
            double orl,
            double pdh,
            double pdl,
            double rangePosition,
            boolean risingVolume
    ) {
        if (side == TradeSide.NONE || ltp <= 0) return 0.0;
        double score = 0.0;
        if (side == TradeSide.LONG) {
            if (vwap > 0 && ltp > vwap) score += 35.0;
            if (orh > 0 && ltp >= orh) score += 30.0;
            if (pdh > 0 && ltp >= pdh) score += 20.0;
            if (rangePosition >= 0.80) score += 15.0;
        } else {
            if (vwap > 0 && ltp < vwap) score += 35.0;
            if (orl > 0 && ltp <= orl) score += 30.0;
            if (pdl > 0 && ltp <= pdl) score += 20.0;
            if (rangePosition <= 0.20) score += 10.0;
            if (risingVolume) score += 5.0;
        }
        return Math.min(100.0, score);
    }

    private double computeRsScore(TradeSide side, double stockRet, double niftyRet, double sectorRet) {
        if (side == TradeSide.NONE) return 0.0;
        double score = 0.0;
        if (side == TradeSide.LONG) {
            if (stockRet > niftyRet) score += 40.0;
            if (sectorRet >= 0 && stockRet > 0) score += 30.0;
            if (stockRet >= sectorRet) score += 30.0;
        } else {
            if (stockRet < niftyRet) score += 40.0;
            if (sectorRet <= 0 && stockRet < 0) score += 30.0;
            if (stockRet <= sectorRet) score += 30.0;
        }
        return Math.min(100.0, score);
    }

    private double computeLiquidityScore(double avgTurnover20dCr, double spreadPct) {
        double turnoverPts = 0.0;
        if (avgTurnover20dCr >= 50.0) turnoverPts = 60.0;
        else if (avgTurnover20dCr >= 20.0) turnoverPts = 30.0 + ((avgTurnover20dCr - 20.0) / 30.0) * 30.0;
        else turnoverPts = Math.max(0.0, (avgTurnover20dCr / 20.0) * 20.0);

        double spreadPts;
        if (spreadPct <= 0.0) spreadPts = 30.0; // spread unknown / off-hours quote
        else if (spreadPct <= 0.05) spreadPts = 40.0;
        else if (spreadPct <= 0.15) spreadPts = 30.0;
        else if (spreadPct <= 0.30) spreadPts = 15.0;
        else spreadPts = 5.0;

        return Math.min(100.0, turnoverPts + spreadPts);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

