package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [earning] A task's stars, the job skill, a task's pay and the glut, checked without a world. */
class EarnMathTest {

    @Test
    @DisplayName("stars follow speed against par, and each mistake or loss of quality costs one")
    void stars() {
        assertEquals(5, EarnMath.stars(240, 160, 0, 0), "served inside par is five");
        assertEquals(4, EarnMath.stars(240, 300, 0, 0), "a little over par is four");
        assertEquals(3, EarnMath.stars(240, 470, 0, 0), "under double par is three");
        assertEquals(2, EarnMath.stars(240, 700, 0, 0), "under triple par is two");
        assertEquals(1, EarnMath.stars(240, 2000, 0, 0), "far over par is one");
        assertEquals(4, EarnMath.stars(240, 100, 1, 0), "a fast task with a mistake loses a star");
        assertEquals(1, EarnMath.stars(240, 100, 3, 3), "never under one for a finished task");
    }

    @Test
    @DisplayName("skill levels climb with experience and raise pay; poor stars pay less")
    void skillAndPay() {
        assertEquals(0, EarnMath.level(0));
        assertEquals(1, EarnMath.level(40));
        assertEquals(4, EarnMath.level(10_000));
        assertTrue(EarnMath.taskPay(2.0, 4, 2, false) > EarnMath.taskPay(2.0, 4, 0, false), "skill raises pay");
        assertTrue(EarnMath.taskPay(2.0, 2, 0, false) < EarnMath.taskPay(2.0, 5, 0, false), "a poor task pays less");
        assertTrue(EarnMath.taskPay(2.0, 4, 2, true) > EarnMath.taskPay(2.0, 4, 2, false), "a senior post pays more");
        assertTrue(EarnMath.poorShift(4, 9) && !EarnMath.poorShift(4, 12) && EarnMath.poorShift(0, 0), "a poor shift is under 2.5 stars");
    }

    @Test
    @DisplayName("a buyer pays its whole bid while it wants plenty, less as it fills, and nothing when full")
    void glut() {
        assertTrue(Math.abs(EarnMath.glutted(1.0, 0, 64) - 1.0) < 1e-9, "the whole bid, empty");
        assertTrue(Math.abs(EarnMath.glutted(1.0, 32, 64) - 1.0) < 1e-9, "the whole bid, half full");
        double nearly = EarnMath.glutted(1.0, 60, 64);
        assertTrue(nearly > 0 && nearly < 0.5, "nearly full pays little: " + nearly);
        assertTrue(EarnMath.glutted(1.0, 64, 64) == 0.0, "nothing when full");
        double last = 2;
        for (int have = 0; have <= 64; have += 4) {
            double g = EarnMath.glutted(1.0, have, 64);
            assertTrue(g <= last + 1e-9, "never rises as it fills");
            last = g;
        }
    }
}
