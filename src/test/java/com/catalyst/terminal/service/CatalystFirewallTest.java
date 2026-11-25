package com.catalyst.terminal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CatalystFirewallTest {

    private final CatalystFirewall firewall = new CatalystFirewall();

    @Test
    void dropsRoutineComplianceNoise() {
        assertFalse(firewall.evaluate("TCS", "Intimation of Closure of Trading Window under SEBI PIT").pass());
        assertFalse(firewall.evaluate("BDL", "Certificate under SEBI Reconciliation of Share Capital Audit").pass());
        assertFalse(firewall.evaluate("XYZ", "Resignation of Company Secretary and Compliance Officer").pass());
        assertFalse(firewall.evaluate("ABC", "Board Meeting Intimation for general corporate matters").pass());
        assertFalse(firewall.evaluate("DEF", "Loss of Share Certificate intimation").pass());
    }

    @Test
    void passesAllInstitutionalCatalystCategories() {
        assertTrue(firewall.evaluate("RVNL", "Receipt of Letter of Acceptance for railway work order worth Rs 485 Crore").pass());
        assertTrue(firewall.evaluate("BEL", "Strategic Joint Venture and technology transfer in Defence electronics").pass());
        assertTrue(firewall.evaluate("SUNPHARMA", "USFDA approval received for key specialty ANDA product").pass());
        assertTrue(firewall.evaluate("KPIGREEN", "Q3 Financial Results: Revenue and PAT jump 55% YoY with margin expansion").pass());
        assertTrue(firewall.evaluate("MIDCAP", "Resignation of Chief Financial Officer and Statutory Auditor").pass());
        assertTrue(firewall.evaluate("TRENT", "Block Deal executed by institutional FII funds").pass());
    }
}

