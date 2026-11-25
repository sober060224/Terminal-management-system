package com.gdou.ferry.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 座位号生成规则的单测。
 *
 * <p>这条规则有两份实现：数据库触发器 {@code trg_schedule_generate_seats}（建班次时）
 * 与 Java 侧 {@link SeatGenerator}（换船重建时）。两份必须逐字一致，
 * 否则会出现「同样的座位数、不同的座号」的怪事，所以这里把规则钉死。
 */
class SeatGeneratorTest {

    private final SeatGenerator generator = new SeatGenerator(null);

    @Test
    void 前四排是ABCD每排四座() {
        List<String> numbers = generator.seatNumbers(16);
        assertEquals(List.of("A1", "A2", "A3", "A4",
                "B1", "B2", "B3", "B4",
                "C1", "C2", "C3", "C4",
                "D1", "D2", "D3", "D4"), numbers);
    }

    @Test
    void 末排不足四座就少几个() {
        // 6 个座位 = A 排 4 个 + B 排 2 个
        assertEquals(List.of("A1", "A2", "A3", "A4", "B1", "B2"), generator.seatNumbers(6));
    }

    @Test
    void 第26排是Z第27排起是两级字母() {
        // 26 排 = 104 座；第 27 排的座号应是 AA1
        List<String> numbers = generator.seatNumbers(108);
        assertEquals("Z4", numbers.get(103));
        assertEquals("AA1", numbers.get(104));
        assertEquals("AA4", numbers.get(107));
    }

    @Test
    void 两级字母最多到AZ然后进BA() {
        // 52 排 = 208 座，第 53 排应是 BA1
        List<String> numbers = generator.seatNumbers(212);
        assertEquals("AZ4", numbers.get(207));
        assertEquals("BA1", numbers.get(208));
    }

    @Test
    void 座位总数等于座号个数() {
        for (int total : new int[]{1, 5, 40, 100, 120, 200, 500}) {
            assertEquals(total, generator.seatNumbers(total).size(), "座位数 " + total);
        }
    }

    @Test
    void 座号必须符合数据库CHECK约束() {
        // ck_seat_no: ^[A-Z]{1,2}[1-4]$
        for (String seatNo : generator.seatNumbers(500)) {
            assertEquals(true, seatNo.matches("^[A-Z]{1,2}[1-4]$"), "座号不合法：" + seatNo);
        }
    }

    @Test
    void 排号换算与触发器一致() {
        assertEquals("A", SeatGenerator.rowLabel(1));
        assertEquals("Z", SeatGenerator.rowLabel(26));
        assertEquals("AA", SeatGenerator.rowLabel(27));
        assertEquals("AZ", SeatGenerator.rowLabel(52));
        assertEquals("BA", SeatGenerator.rowLabel(53));
        assertEquals("BZ", SeatGenerator.rowLabel(78));
    }
}
