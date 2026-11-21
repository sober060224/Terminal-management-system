package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrdersRepository extends JpaRepository<Orders, Long> {

    List<Orders> findByPassengerIdOrderByCreateTimeDesc(Long passengerId);

    List<Orders> findBySellerIdOrderByCreateTimeDesc(Long sellerId);

    /**
     * 状态迁移用条件更新：并发支付、取消、超时清理里只有一方能改到行，返回受影响行数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Orders o set o.status = '已支付', o.payTime = :now, o.expireAt = null "
            + "where o.id = :id and o.status = '待支付' and (o.expireAt is null or o.expireAt > :now)")
    int markPaid(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Orders o set o.status = '已取消' where o.id = :id and o.status = '待支付'")
    int markCancelled(@Param("id") Long id);

    @Query("select o.id from Orders o where o.status = :status and o.expireAt < :expireAt")
    List<Long> findIdsByStatusAndExpireAtBefore(@Param("status") String status,
                                                @Param("expireAt") LocalDateTime expireAt);
}
