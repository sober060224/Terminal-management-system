package com.gdou.ferry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 订票 / 退改相关的表单 DTO
 */
public class OrderForms {

    /**
     * 单个座位的乘船人信息
     */
    public record SeatRequest(
            @NotBlank(message = "座位号不能为空")
            String seatNo,

            @NotBlank(message = "乘船人姓名不能为空")
            String passengerName,

            @NotBlank(message = "乘船人身份证号不能为空")
            @Size(min = 18, max = 18, message = "身份证号必须为18位")
            String idCard
    ) {}

    /**
     * 创建订单（选座购票 / 代售）
     */
    public record CreateOrderRequest(
            @NotNull(message = "班次不能为空")
            Long scheduleId,

            @NotEmpty(message = "至少选择一个座位")
            List<SeatRequest> seats
    ) {}

    /**
     * 改签：目标班次 + 目标座位
     */
    public record RebookRequest(
            @NotNull(message = "目标班次不能为空")
            Long targetScheduleId,

            @NotNull(message = "目标座位不能为空")
            Long targetSeatId
    ) {}
}
