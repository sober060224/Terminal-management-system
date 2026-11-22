package com.gdou.ferry.controller;

import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.common.Result;
import com.gdou.ferry.dto.OrderForms;
import com.gdou.ferry.dto.OrderRowView;
import com.gdou.ferry.dto.OrderView;
import com.gdou.ferry.dto.TicketView;
import com.gdou.ferry.service.OrderQueryService;
import com.gdou.ferry.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
/**
 * 售票员侧订单：订单列表、代售下单、代付、代取消、代退、代改签。
 * 代售订单 passenger_id 与 seller_id 都挂售票员本人，真实乘船人信息写在票上。
 */
@RestController
@RequestMapping("/api/seller/orders")
public class SellerOrderController {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;

    public SellerOrderController(OrderService orderService, OrderQueryService orderQueryService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
    }

    @GetMapping
    public Result<List<OrderRowView>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Long scheduleId,
            @RequestParam(required = false) String passengerName,
            @AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(orderQueryService.sellerOrders(loginUser, dateFrom, dateTo, scheduleId, passengerName));
    }

    /** 代售：passengerId 与 sellerId 都取登录售票员。 */
    @PostMapping
    public Result<OrderView> create(@Valid @RequestBody OrderForms.CreateOrderRequest form,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        var order = orderService.createOrder(form.scheduleId(), loginUser.getId(), loginUser.getId(), form.seats());
        return Result.ok("代售成功", OrderView.of(order));
    }

    @PostMapping("/{id}/pay")
    public Result<OrderView> pay(@PathVariable Long id,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok("支付成功", OrderView.of(orderService.payOrder(id, loginUser)));
    }

    @PostMapping("/{id}/cancel")
    public Result<OrderView> cancel(@PathVariable Long id,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok("订单已取消", OrderView.of(orderService.cancelOrder(id, loginUser)));
    }

    @PostMapping("/tickets/{ticketId}/refund")
    public Result<TicketView> refund(@PathVariable Long ticketId,
                                     @RequestBody(required = false) OrderForms.RefundRequest form,
                                     @AuthenticationPrincipal LoginUser loginUser) {
        var ticket = orderService.refundTicket(ticketId, form == null ? null : form.reason(), loginUser);
        return Result.ok("退票成功", TicketView.of(ticket));
    }

    @PostMapping("/tickets/{ticketId}/rebook")
    public Result<TicketView> rebook(@PathVariable Long ticketId,
                                     @Valid @RequestBody OrderForms.RebookRequest form,
                                     @AuthenticationPrincipal LoginUser loginUser) {
        var ticket = orderService.rebookTicket(ticketId, form.targetScheduleId(), form.targetSeatId(), loginUser);
        return Result.ok("改签成功", TicketView.of(ticket));
    }
}
