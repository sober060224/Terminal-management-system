package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findByOrderIdOrderByIdAsc(Long orderId);

    /**
     * 一人一票判重：该乘船人在该班次是否已有未作废（非已退票/已取消）的票。
     */
    @Query("select count(t) > 0 from Ticket t where t.idCard = :idCard and t.scheduleId = :scheduleId "
            + "and t.status not in ('已退票', '已取消')")
    boolean existsActiveTicket(@Param("idCard") String idCard, @Param("scheduleId") Long scheduleId);
}
