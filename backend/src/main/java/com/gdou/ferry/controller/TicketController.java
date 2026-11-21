package com.gdou.ferry.controller;

import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.common.Result;
import com.gdou.ferry.dto.OrderForms;
import com.gdou.ferry.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 船票退改：退票、改签。乘客只能操作自己订单下的票，售票员/管理员可代操作。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final OrderService orderService;

    public TicketController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/{id}/refund")
    public Result<Map<String, Object>> refund(@PathVariable Long id,
                                              @AuthenticationPrincipal LoginUser loginUser) {
        var ticket = orderService.refundTicket(id, loginUser);
        return Result.ok("退票成功", com.gdou.ferry.dto.OrderViews.ticket(ticket));
    }

    @PostMapping("/{id}/rebook")
    public Result<Map<String, Object>> rebook(@PathVariable Long id,
                                              @Valid @RequestBody OrderForms.RebookRequest form,
                                              @AuthenticationPrincipal LoginUser loginUser) {
        var ticket = orderService.rebookTicket(id, form.targetScheduleId(), form.targetSeatId(), loginUser);
        return Result.ok("改签成功", com.gdou.ferry.dto.OrderViews.ticket(ticket));
    }
}
