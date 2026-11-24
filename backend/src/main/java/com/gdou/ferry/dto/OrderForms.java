package com.gdou.ferry.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
            String idCard,

            @NotBlank(message = "乘船人手机号不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone
    ) {}

    /**
     * 创建订单（选座购票 / 代售）
     */
    public record CreateOrderRequest(
            @NotNull(message = "班次不能为空")
            Long scheduleId,

            @NotEmpty(message = "至少选择一个座位")
            @Size(max = 5, message = "一个订单最多 5 张票")
            List<@Valid @NotNull SeatRequest> seats
    ) {}

    /**
     * 退票原因（可选，不传按「乘客退票」处理，兼容旧调用方）
     */
    public record RefundRequest(String reason) {}

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
