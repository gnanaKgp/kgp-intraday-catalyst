package com.catalyst.terminal.service.dhan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TotpUtilTest {

    @Test
    void matchesRfc6238Sha1Vector() {
        // RFC 6238 ASCII "12345678901234567890" in Base32 = GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        String code = TotpUtil.generateCodeAt("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59L);
        assertEquals("94287082".substring(2), code);
    }
}

