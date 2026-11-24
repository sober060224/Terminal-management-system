package com.gdou.ferry.service;

import com.gdou.ferry.repository.OrdersRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;

/**
 * 定时清理超时未支付订单（ADR-0003：@Scheduled 只做清理，不参与锁判断）。
 */
@Service
public class OrderExpiryJob {

    private final OrdersRepository ordersRepository;
    private final OrderService orderService;
    private final Clock clock;

    public OrderExpiryJob(OrdersRepository ordersRepository, OrderService orderService, Clock clock) {
        this.ordersRepository = ordersRepository;
        this.orderService = orderService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void expireStaleOrders() {
        for (Long orderId : ordersRepository.findIdsByStatusAndExpireAtBefore("待支付", LocalDateTime.now(clock))) {
            orderService.cancelAsSystem(orderId);
        }
    }
}
