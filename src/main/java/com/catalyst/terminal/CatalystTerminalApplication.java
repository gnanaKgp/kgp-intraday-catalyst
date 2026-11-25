package com.catalyst.terminal;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@ConfigurationPropertiesScan
public class CatalystTerminalApplication {

    private static final Logger log = LoggerFactory.getLogger(CatalystTerminalApplication.class);

    @PostConstruct
    public void initTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        log.info("System timezone configured to {}", TimeZone.getDefault().getID());
    }

    public static void main(String[] args) {
        SpringApplication.run(CatalystTerminalApplication.class, args);
        log.info("NSE Institutional Catalyst Terminal started.");
    }
}

