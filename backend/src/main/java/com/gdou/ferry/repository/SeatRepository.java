package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScheduleIdOrderByIdAsc(Long scheduleId);

    Optional<Seat> findByScheduleIdAndSeatNo(Long scheduleId, String seatNo);

    long countByScheduleIdAndSeatStatus(Long scheduleId, String seatStatus);

    /**
     * 释放座位，只清「属于该订单」的锁（order_id 是座位与订单的归属标记）。
     * 原订单超时后座位可能已被新订单抢走，必须按 order_id 限定，否则会把新订单的座位一起清空。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Seat s set s.seatStatus = '可选', s.lockUntil = null, s.orderId = null "
            + "where s.id = :seatId and s.orderId = :orderId")
    int releaseByOrder(@Param("seatId") Long seatId, @Param("orderId") Long orderId);
}
