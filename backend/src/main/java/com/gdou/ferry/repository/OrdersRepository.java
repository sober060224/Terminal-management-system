package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Orders;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OrdersRepository extends JpaRepository<Orders, Long> {

    List<Orders> findByPassengerIdOrderByCreateTimeDesc(Long passengerId);

    List<Orders> findBySellerIdOrderByCreateTimeDesc(Long sellerId);

    List<Orders> findByStatusAndExpireAtBefore(String status, LocalDateTime expireAt);
}
