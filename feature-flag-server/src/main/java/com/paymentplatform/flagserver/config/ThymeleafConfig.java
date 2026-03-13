package com.paymentplatform.flagserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Configuration
public class ThymeleafConfig {

    @Bean(name = "fmt")
    public DateFormatter dateFormatter() {
        return new DateFormatter();
    }

    public static class DateFormatter {

        private static final DateTimeFormatter FORMATTER =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        private static final DateTimeFormatter SHORT_FORMATTER =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

        public String instant(Instant instant) {
            if (instant == null) {
                return "-";
            }
            return FORMATTER.format(instant);
        }

        public String shortInstant(Instant instant) {
            if (instant == null) {
                return "-";
            }
            return SHORT_FORMATTER.format(instant);
        }
    }
}
