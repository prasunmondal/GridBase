package io.github.prasunmondal.hibernatesheets.integrationTests;

import io.github.prasunmondal.hibernatesheets.HibernateSheets;
import io.github.prasunmondal.hibernatesheets.RetryPolicy;
import io.github.prasunmondal.hibernatesheets.SheetProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Connection settings for the integration tests.
 *
 * <p>Defaults point at the live deployment used in {@code Test1}. Override without editing code:</p>
 * <pre>
 *   mvn verify -Dhs.endpoint=https://script.google.com/macros/s/&lt;id&gt;/exec -Dhs.spreadsheetId=&lt;id&gt;
 *   # or env vars HS_ENDPOINT / HS_SPREADSHEET_ID
 *   # local emulator: -Dhs.endpoint=http://127.0.0.1:8765/macros/s/LOCAL/exec
 * </pre>
 *
 * <p>The tests only touch worksheets whose names start with {@code IT_}.</p>
 */
public final class ItConfig {

    public static final String ENDPOINT = setting("hs.endpoint", "HS_ENDPOINT",
            "https://script.google.com/macros/s/AKfycbzmkjT0S4fJQuBY7Dk5h7JA1cylptUO6EFsshEoaOvj-mRXjE422A_QBl1JiOHy3f9p/exec");

    public static final String SPREADSHEET_ID = setting("hs.spreadsheetId", "HS_SPREADSHEET_ID",
            "1C8rsAWa0XfpxfHSb-F-FALSvmCT1knQ5lBoegQ8Phwc");

    public static final ZoneId ZONE = ZoneId.of(setting("hs.timeZone", "HS_TIME_ZONE", "Asia/Kolkata"));

    /** Connection settings shared by the entity classes; each derives its own via {@code toBuilder()}. */
    public static final SheetProperties BASE_PROPERTIES = SheetProperties.builder()
            .scriptUrl(ENDPOINT)
            .dbSheetUrl(SPREADSHEET_ID)
            .timeZone(ZONE)
            .requestTimeout(Duration.ofSeconds(120))
            .retryPolicy(RetryPolicy.defaults())
            .build();

    private static volatile HibernateSheets db;

    private ItConfig() {
    }

    /** Shared client (thread-safe). Reads are retried; writes are not. */
    public static HibernateSheets db() {
        if (db == null) {
            synchronized (ItConfig.class) {
                if (db == null) {
                    db = HibernateSheets.builder()
                            .endpoint(ENDPOINT)
                            .defaultSpreadsheetId(SPREADSHEET_ID)
                            .timeZone(ZONE)
                            .requestTimeout(Duration.ofSeconds(120))
                            .retryPolicy(RetryPolicy.defaults())
                            .build();
                }
            }
        }
        return db;
    }

    private static String setting(String property, String env, String fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
