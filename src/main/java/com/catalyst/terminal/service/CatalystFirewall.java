package com.catalyst.terminal.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 0ms pre-filter aligned with all 6 institutional catalyst categories:
 * ORDER_WIN, JOINT_VENTURE, EARNINGS, REGULATORY, CORPORATE_ACTION, BLOCK_DEAL.
 */
@Service
public class CatalystFirewall {

    private static final List<String> HARD_NOISE_PHRASES = List.of(
            "trading window",
            "loss of share",
            "duplicate share",
            "certificate under sebi",
            "reconciliation of share capital",
            "newspaper publication",
            "postal ballot",
            "agm notice",
            "egm notice",
            "annual general meeting",
            "extraordinary general meeting",
            "analyst/investor meet",
            "analysts/institutional investor meet",
            "investor presentation",
            "transcript of earnings call",
            "appointment of company secretary",
            "resignation of company secretary",
            "independent director",
            "compliance officer"
    );

    private static final List<String> CATALYST_TRIGGERS = List.of(
            // Order win & JV
            "order", "contract", "award", "loa", "letter of award", "letter of intent", "loi",
            "mou", "bagged", "won", "joint venture", "tech transfer", "technology transfer",
            "supply", "epc", "crore", "crores", "usd", "million", "billion",
            // Earnings & guidance
            "financial results", "quarterly results", "q1", "q2", "q3", "q4",
            "ebitda", "pat", "revenue", "guidance", "margin expansion", "profit jump",
            // Regulatory & executive shock
            "usfda", "fda", "sebi", "warning letter", "approval", "anda", "inspection",
            "cfo", "chief financial officer", "auditor", "plant shutdown", "ban", "penalty", "recall",
            // Corporate actions & deals
            "capex", "capacity expansion", "commissioning", "acquisition", "acquire",
            "buyback", "demerger", "bulk deal", "block deal", "stake sale", "press release"
    );

    public FirewallDecision evaluate(String title, String description) {
        String combined = ((title != null ? title : "") + " " + (description != null ? description : "")).toLowerCase();

        for (String noise : HARD_NOISE_PHRASES) {
            if (combined.contains(noise)) {
                return FirewallDecision.drop("Noise rule matched: " + noise);
            }
        }

        // Routine board meeting intimation without results/buyback/bonus context
        if (combined.contains("board meeting intimation") && !combined.contains("results")
                && !combined.contains("buyback") && !combined.contains("acquisition")) {
            return FirewallDecision.drop("Routine board meeting intimation");
        }

        // Resignation is noise UNLESS it involves CFO / Auditor / MD / CEO
        if (combined.contains("resignation")
                && !combined.contains("cfo")
                && !combined.contains("chief financial officer")
                && !combined.contains("auditor")
                && !combined.contains("chief executive")
                && !combined.contains("ceo")
                && !combined.contains("managing director")) {
            return FirewallDecision.drop("Routine officer/director resignation");
        }

        for (String trigger : CATALYST_TRIGGERS) {
            if (combined.contains(trigger)) {
                return FirewallDecision.pass("Catalyst trigger: " + trigger);
            }
        }

        return FirewallDecision.drop("No institutional catalyst keyword matched");
    }

    public record FirewallDecision(boolean pass, String reason) {
        public static FirewallDecision pass(String reason) {
            return new FirewallDecision(true, reason);
        }

        public static FirewallDecision drop(String reason) {
            return new FirewallDecision(false, reason);
        }
    }
}

