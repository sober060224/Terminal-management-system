package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    /**
     * 支付置售：与 releaseByOrder 对称，只认「仍挂在本订单名下且处于锁定」的座位。
     * 返回 0 说明座位已被别的订单抢走或已被处理，调用方必须整单回滚（P1-1）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Seat s set s.seatStatus = '已售', s.lockUntil = null, s.orderId = :orderId "
            + "where s.id = :seatId and s.orderId = :orderId and s.seatStatus = '锁定'")
    int sellByOrder(@Param("seatId") Long seatId, @Param("orderId") Long orderId);

    /**
     * 改签置售：目标座位此时可能还没挂订单（可选）也可能被别人短锁（锁定），
     * 已售的一律不动。返回 0 说明座位已被占用，调用方回滚。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Seat s set s.seatStatus = '已售', s.lockUntil = null, s.orderId = :orderId "
            + "where s.id = :seatId and s.scheduleId = :scheduleId "
            + "and s.seatStatus in ('可选', '锁定')")
    int sellTargetSeat(@Param("seatId") Long seatId, @Param("scheduleId") Long scheduleId,
                       @Param("orderId") Long orderId);

    /** 余票口径：已售 + 未过期的锁定都算占用（与视图 v_schedule_remaining 一致）。 */
    @Query("select count(s) from Seat s where s.scheduleId = :scheduleId "
            + "and (s.seatStatus = '已售' or (s.seatStatus = '锁定' "
            + "and (s.lockUntil is null or s.lockUntil > :now)))")
    long countUnavailable(@Param("scheduleId") Long scheduleId, @Param("now") LocalDateTime now);

    /**
     * 换船时删掉旧座位重建。批量删除比逐条 save/delete 少 N 次往返。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Seat s where s.scheduleId = :scheduleId")
    int deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
