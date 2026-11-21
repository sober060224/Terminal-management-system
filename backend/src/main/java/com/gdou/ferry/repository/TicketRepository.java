package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByOrderIdOrderByIdAsc(Long orderId);

    /** 订单列表用：一次取回多单的票，避免按单查询。 */
    List<Ticket> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    /**
     * 一人一票判重：该乘船人在该班次是否已有未作废（非已退票/已取消）的票。
     */
    @Query("select count(t) > 0 from Ticket t where t.idCard = :idCard and t.scheduleId = :scheduleId "
            + "and t.status not in ('已退票', '已取消')")
    boolean existsActiveTicket(@Param("idCard") String idCard, @Param("scheduleId") Long scheduleId);

    /**
     * 退票与改签共用：只有仍是「已支付」的票能落退票流水，重复提交返回 0 行。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Ticket t set t.status = '已退票', t.refundAmount = :refundAmount, "
            + "t.refundReason = :refundReason, t.refundTime = :refundTime "
            + "where t.id = :id and t.status = '已支付'")
    int markRefunded(@Param("id") Long id, @Param("refundAmount") BigDecimal refundAmount,
                     @Param("refundReason") String refundReason, @Param("refundTime") LocalDateTime refundTime);
}
