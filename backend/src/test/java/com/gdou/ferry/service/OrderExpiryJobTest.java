package com.gdou.ferry.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.gdou.ferry.repository.OrdersRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExpiryJobTest {

    @Mock
    private OrdersRepository ordersRepository;
    @Mock
    private OrderService orderService;

    private OrderExpiryJob job;

    @BeforeEach
    void setUp() {
        job = new OrderExpiryJob(ordersRepository, orderService,
                Clock.fixed(LocalDateTime.of(2026, 11, 21, 12, 0).atZone(ZoneId.systemDefault()).toInstant(),
                        ZoneId.systemDefault()));
    }

    @Test
    void 超时订单逐个走系统取消入口() {
        when(ordersRepository.findIdsByStatusAndExpireAtBefore("待支付", LocalDateTime.of(2026, 11, 21, 12, 0)))
                .thenReturn(List.of(1L, 2L));
        when(orderService.cancelAsSystem(1L)).thenReturn(true);
        when(orderService.cancelAsSystem(2L)).thenReturn(true);

        job.expireStaleOrders();

        verify(orderService).cancelAsSystem(1L);
        verify(orderService).cancelAsSystem(2L);
    }

    @Test
    void 已被抢先改状态的订单不抛异常() {
        when(ordersRepository.findIdsByStatusAndExpireAtBefore("待支付", LocalDateTime.of(2026, 11, 21, 12, 0)))
                .thenReturn(List.of(1L));
        when(orderService.cancelAsSystem(1L)).thenReturn(false);

        assertDoesNotThrow(() -> job.expireStaleOrders());
    }
}
