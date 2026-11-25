package com.gdou.ferry.service;

import com.gdou.ferry.entity.Seat;
import com.gdou.ferry.repository.SeatRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 座位号生成器。
 *
 * <p>规则与数据库触发器 {@code trg_schedule_generate_seats} **逐字一致**：
 * 每排 4 座，排号从 A 开始，第 27 排起改用两级字母（AA…AZ、BA…），
 * 末排不足 4 个就少几个。座号形如 {@code A1}、{@code AB4}，受 {@code ck_seat_no} 约束。
 *
 * <p>用途只有一个：**换船时重建座位**。新建班次的座位由数据库触发器生成，
 * 不在这里生成——同一个班次的两条生成路径必须只有一条，否则会重复插行。
 */
@Component
public class SeatGenerator {

    private static final int SEATS_PER_ROW = 4;
    private static final int ROWS_PER_LEVEL = 26;

    private final SeatRepository seatRepository;

    public SeatGenerator(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    /**
     * 生成某个班次的座位号列表（不落库），供重建时插入。
     *
     * @param totalSeats 座位总数
     * @return 座位号列表，长度等于 totalSeats
     */
    public List<String> seatNumbers(int totalSeats) {
        List<String> numbers = new ArrayList<>(totalSeats);
        for (int i = 1; i <= totalSeats; i++) {
            int rowNumber = (i - 1) / SEATS_PER_ROW + 1;
            int seatInRow = (i - 1) % SEATS_PER_ROW + 1;
            numbers.add(rowLabel(rowNumber) + seatInRow);
        }
        return numbers;
    }

    /**
     * 排号字母：1–26 排是 A…Z，27–52 排是 AA…AZ，53–78 排是 BA…BZ。
     *
     * <p>不能直接用 {@code CHAR(64 + rowNumber)}：第 27 排会算出 '[' 这种畸形字符。
     * <p>换算与触发器逐字对齐：{@code CHAR(64 + FLOOR((row - 1) / 26))} 配
     * {@code CHAR(65 + MOD(row - 1, 26))}，即首字母是 {@code 'A' + level - 1}。
     */
    static String rowLabel(int rowNumber) {
        if (rowNumber <= ROWS_PER_LEVEL) {
            return String.valueOf((char) ('A' + rowNumber - 1));
        }
        int level = (rowNumber - 1) / ROWS_PER_LEVEL;
        int withinLevel = (rowNumber - 1) % ROWS_PER_LEVEL;
        return String.valueOf((char) ('A' + level - 1)) + (char) ('A' + withinLevel);
    }

    /**
     * 删除该班次的旧座位并按新座位数重建。
     *
     * @param scheduleId 班次 ID
     * @param totalSeats 新的座位总数
     * @return 重建出的座位数
     */
    public int rebuild(Long scheduleId, int totalSeats) {
        seatRepository.deleteByScheduleId(scheduleId);
        List<Seat> seats = new ArrayList<>(totalSeats);
        for (String seatNo : seatNumbers(totalSeats)) {
            Seat seat = new Seat();
            seat.setScheduleId(scheduleId);
            seat.setSeatNo(seatNo);
            seat.setSeatStatus("可选");
            seats.add(seat);
        }
        seatRepository.saveAll(seats);
        return seats.size();
    }
}
