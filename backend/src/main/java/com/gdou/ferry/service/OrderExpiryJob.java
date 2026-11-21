package com.gdou.ferry.service;

import com.gdou.ferry.repository.OrdersRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 定时清理超时未支付订单（ADR-0003：@Scheduled 只做清理，不参与锁判断）。
 */
@Service
public class OrderExpiryJob {

    private final OrdersRepository ordersRepository;
    private final OrderService orderService;

    public OrderExpiryJob(OrdersRepository ordersRepository, OrderService orderService) {
        this.ordersRepository = ordersRepository;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void expireStaleOrders() {
        for (Long orderId : ordersRepository.findIdsByStatusAndExpireAtBefore("待支付", LocalDateTime.now())) {
            orderService.cancelAsSystem(orderId);
        }
    }
}
