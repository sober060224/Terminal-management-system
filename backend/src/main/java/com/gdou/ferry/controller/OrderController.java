package com.gdou.ferry.controller;

import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.common.Result;
import com.gdou.ferry.dto.OrderForms;
import com.gdou.ferry.dto.OrderRowView;
import com.gdou.ferry.dto.OrderView;
import com.gdou.ferry.service.OrderQueryService;
import com.gdou.ferry.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 乘客侧订单：我的订单、自购下单、支付、取消。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;

    public OrderController(OrderService orderService, OrderQueryService orderQueryService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
    }

    @GetMapping("/mine")
    public Result<List<OrderRowView>> mine(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(orderQueryService.myOrders(loginUser.getId()));
    }

    /** 乘客自购：passengerId 取登录人，sellerId 留空。 */
    @PostMapping
    public Result<OrderView> create(@Valid @RequestBody OrderForms.CreateOrderRequest form,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        var order = orderService.createOrder(form.scheduleId(), loginUser.getId(), null, form.seats());
        return Result.ok("下单成功", orderView(order));
    }

    @PostMapping("/{id}/pay")
    public Result<OrderView> pay(@PathVariable Long id,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok("支付成功", orderView(orderService.payOrder(id, loginUser)));
    }

    @PostMapping("/{id}/cancel")
    public Result<OrderView> cancel(@PathVariable Long id,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok("订单已取消", orderView(orderService.cancelOrder(id, loginUser)));
    }

    private OrderView orderView(com.gdou.ferry.entity.Orders order) {
        return OrderView.of(order);
    }
}
