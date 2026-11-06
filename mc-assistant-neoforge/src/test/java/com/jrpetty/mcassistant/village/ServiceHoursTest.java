package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [shops-homes] A service point's hours: open and shut, past midnight, and when it opens next, by the clock. */
class ServiceHoursTest {

    private static final ServiceHours SHOP = new ServiceHours(1000, 13000), TAVERN = new ServiceHours(10500, 19500);

    @Test
    @DisplayName("a day's hours: open from its opening, shut at its closing")
    void dayHours() {
        assertFalse(SHOP.isOpen(999));
        assertTrue(SHOP.isOpen(1000));
        assertTrue(SHOP.isOpen(12999));
        assertFalse(SHOP.isOpen(13000));
        assertTrue(SHOP.isOpen(24000L * 5 + 2000));
        assertEquals("7am to 7pm", SHOP.words());
    }

    @Test
    @DisplayName("hours past midnight wrap round")
    void wrapping() {
        assertTrue(TAVERN.isOpen(12000));
        assertTrue(TAVERN.isOpen(19000));
        assertFalse(TAVERN.isOpen(20000));
        assertFalse(TAVERN.isOpen(2000));
        assertEquals("4:30pm to 1:30am", TAVERN.words());
    }

    @Test
    @DisplayName("when it opens next, today or tomorrow by the clock on the wall")
    void nextOpening() {
        assertEquals("Opens at 7am tomorrow", SHOP.nextOpening(15000));     // 9pm
        assertEquals("Opens at 7am", SHOP.nextOpening(20000));               // 2am: later today
        assertEquals("Open now", SHOP.nextOpening(5000));
        assertEquals(1000, SHOP.untilOpen(0));
        assertTrue(ServiceHours.ALWAYS.isOpen(17777));
    }
}
