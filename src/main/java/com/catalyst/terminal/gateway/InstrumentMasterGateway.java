package com.catalyst.terminal.gateway;

import java.util.Optional;

public interface InstrumentMasterGateway {
    Optional<String> downloadInstrumentsCsv();
}

