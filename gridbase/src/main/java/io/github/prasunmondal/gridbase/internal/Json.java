package io.github.prasunmondal.gridbase.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Builds the ObjectMapper used for request/response JSON and row-to-object mapping. */
public final class Json {

    private Json() {
    }

    /**
     * @param base user-supplied mapper to start from (copied, never mutated) or {@code null}
     * @param zone spreadsheet time zone, used to turn date cells into {@code LocalDate}/{@code LocalDateTime}
     */
    public static ObjectMapper mapper(ObjectMapper base, ZoneId zone) {
        ObjectMapper mapper = base == null ? new ObjectMapper() : base.copy();
        mapper.registerModule(new JavaTimeModule());
        // Registered after JavaTimeModule so these take precedence.
        mapper.registerModule(sheetDateModule(zone));
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        // Empty cells arrive as "", which should become null for numbers, dates, enums, ...
        mapper.coercionConfigDefaults().setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
        return mapper;
    }

    private static SimpleModule sheetDateModule(ZoneId zone) {
        SimpleModule module = new SimpleModule("hibernate-sheets-dates");
        module.addDeserializer(LocalDate.class, new JsonDeserializer<LocalDate>() {
            @Override
            public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                LocalDateTime dt = parseDateTime(p, ctxt, zone, LocalDate.class);
                return dt == null ? null : dt.toLocalDate();
            }
        });
        module.addDeserializer(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return parseDateTime(p, ctxt, zone, LocalDateTime.class);
            }
        });
        return module;
    }

    /**
     * Date cells are serialized by Apps Script as UTC instants ("2026-09-29T18:30:00.000Z" is
     * 30 Sep 2026 00:00 in Asia/Kolkata). Converting in the spreadsheet zone gives the date the user
     * actually sees in the sheet; plain "yyyy-MM-dd" text cells are parsed as-is.
     */
    private static LocalDateTime parseDateTime(JsonParser p, DeserializationContext ctxt,
                                               ZoneId zone, Class<?> target) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return Instant.ofEpochMilli(p.getLongValue()).atZone(zone).toLocalDateTime();
        }
        String text = p.getValueAsString();
        if (text == null || Compat.isBlank(text)) {
            return null;
        }
        text = text.trim();
        try {
            return Instant.parse(text).atZone(zone).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // not an instant
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // not a local date-time
        }
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (DateTimeParseException e) {
            throw ctxt.weirdStringException(text, target, "not an ISO date/time");
        }
    }
}
