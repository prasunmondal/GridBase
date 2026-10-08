package io.github.prasunmondal.hibernatesheets;

import io.github.prasunmondal.hibernatesheets.internal.JsCompat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Expected values are what Node/V8 prints for String(x). */
class JsCompatTest {

    @Test
    void numbersMatchJavaScript() {
        assertEquals("5", JsCompat.toJsString(5.0));
        assertEquals("0.1", JsCompat.toJsString(0.1));
        assertEquals("0.1", JsCompat.toJsString(0.1f));
        assertEquals("-3.25", JsCompat.toJsString(-3.25));
        assertEquals("0", JsCompat.toJsString(-0.0));
        assertEquals("1e+21", JsCompat.toJsString(1e21));
        assertEquals("100000000000000000000", JsCompat.toJsString(1e20));
        assertEquals("1e-7", JsCompat.toJsString(1e-7));
        assertEquals("1.5e-7", JsCompat.toJsString(1.5e-7));
        assertEquals("0.000001", JsCompat.toJsString(1e-6));
        assertEquals("12.5", JsCompat.toJsString(new BigDecimal("12.500")));
        assertEquals("NaN", JsCompat.toJsString(Double.NaN));
        assertEquals("-Infinity", JsCompat.toJsString(Double.NEGATIVE_INFINITY));
        assertEquals("9007199254740993", JsCompat.toJsString(9007199254740993L));
    }

    @Test
    void otherTypes() {
        assertEquals("true", JsCompat.toJsString(true));
        assertEquals("text", JsCompat.toJsString("text"));
        assertEquals("DESC", JsCompat.toJsString(io.github.prasunmondal.hibernatesheets.query.Sort.Direction.DESC));
    }
}
