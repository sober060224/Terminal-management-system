package com.gdou.ferry.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 模块二（航线与班次管理）的表单 DTO。
 *
 * <p>校验注解给中文提示，前端按 {@code message} 直接显示。
 */
public class AdminForms {

    /**
     * 航线表单（新增 / 编辑通用）。
     */
    public record RouteForm(
            @NotBlank(message = "航线名称不能为空")
            @Size(max = 100, message = "航线名称不能超过 100 个字符")
            String routeName,

            @NotBlank(message = "出发港不能为空")
            @Size(max = 50, message = "出发港不能超过 50 个字符")
            String startPort,

            @NotBlank(message = "到达港不能为空")
            @Size(max = 50, message = "到达港不能超过 50 个字符")
            String endPort,

            @NotNull(message = "航程时长不能为空")
            @Min(value = 1, message = "航程时长必须在 1-1440 分钟之间")
            @Max(value = 1440, message = "航程时长必须在 1-1440 分钟之间")
            Integer durationMin,

            @Size(max = 200, message = "航线描述不能超过 200 个字符")
            String description,

            @Pattern(regexp = "正常|停运", message = "航线状态只能是正常或停运")
            String status
    ) {}

    /**
     * 船舶表单（新增 / 编辑通用）。
     */
    public record ShipForm(
            @NotBlank(message = "船舶名称不能为空")
            @Size(max = 50, message = "船舶名称不能超过 50 个字符")
            String shipName,

            @NotNull(message = "总座位数不能为空")
            @Min(value = 1, message = "总座位数必须在 1-500 之间")
            @Max(value = 500, message = "总座位数必须在 1-500 之间")
            Integer totalSeats,

            @NotBlank(message = "船舶类型不能为空")
            @Pattern(regexp = "普通船|高速船|豪华船", message = "船舶类型只能是普通船、高速船或豪华船")
            String shipType,

            @Size(max = 200, message = "船舶描述不能超过 200 个字符")
            String description,

            @Pattern(regexp = "正常|维修|停用", message = "船舶状态只能是正常、维修或停用")
            String status
    ) {}

    /**
     * 新增班次：到达时间与座位数由服务端算，前端不传。
     */
    public record ScheduleForm(
            @NotNull(message = "航线不能为空")
            Long routeId,

            @NotNull(message = "船舶不能为空")
            Long shipId,

            @NotNull(message = "发船时间不能为空")
            LocalDateTime departTime,

            @NotNull(message = "票价不能为空")
            @DecimalMin(value = "0.00", message = "票价不能为负")
            BigDecimal price
    ) {}

    /**
     * 编辑班次：字段全部可空，只改传了的字段。
     */
    public record ScheduleUpdateForm(
            Long shipId,

            LocalDateTime departTime,

            @DecimalMin(value = "0.00", message = "票价不能为负")
            BigDecimal price
    ) {}

    /**
     * 批量生成班次（需求 2.2 功能3）。
     */
    public record BatchScheduleForm(
            @NotNull(message = "航线不能为空")
            Long routeId,

            @NotNull(message = "船舶不能为空")
            Long shipId,

            @NotNull(message = "开始日期不能为空")
            LocalDate dateFrom,

            @NotNull(message = "结束日期不能为空")
            LocalDate dateTo,

            @NotNull(message = "每日发船时刻不能为空")
            LocalTime departTime,

            @NotNull(message = "票价不能为空")
            @DecimalMin(value = "0.00", message = "票价不能为负")
            BigDecimal price
    ) {}
}
