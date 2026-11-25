package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.dto.AdminForms;
import com.gdou.ferry.dto.AdminViews;
import com.gdou.ferry.entity.Route;
import com.gdou.ferry.repository.RouteRepository;
import com.gdou.ferry.repository.ScheduleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模块二·功能1：航线管理。
 *
 * <p>删除与编辑的前置校验（需求 2.3-4、2.2 功能1）：
 * <ul>
 *   <li>该航线有「未完成班次」（待发布 / 售票中）→ 不允许删除；</li>
 *   <li>该航线只有历史班次 → 物理删除会被外键拦，改成提示「改为停运」；</li>
 *   <li>该航线已有班次 → 不允许改航程时长（改了历史班次的到达时间就对不上了）。</li>
 * </ul>
 */
@Service
public class RouteService {

    /** 「未完成班次」= 还在卖票或还没发布的班次（PLAN.md §1.1）。 */
    static final List<String> UNFINISHED_STATUSES = List.of("待发布", "售票中");

    private final RouteRepository routeRepository;
    private final ScheduleRepository scheduleRepository;

    public RouteService(RouteRepository routeRepository, ScheduleRepository scheduleRepository) {
        this.routeRepository = routeRepository;
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * 航线分页列表：支持按名称、出发港、到达港模糊搜索。
     */
    @Transactional(readOnly = true)
    public AdminViews.PageResult<AdminViews.RouteView> search(String name, String startPort, String endPort,
                                                              int page, int size) {
        Page<Route> result = routeRepository.search(blankToNull(name), blankToNull(startPort), blankToNull(endPort),
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "id")));
        return AdminViews.PageResult.of(result.map(AdminViews.RouteView::from));
    }

    /**
     * 航线详情：含该航线下的全部班次。
     */
    @Transactional(readOnly = true)
    public AdminViews.RouteDetailView detail(Long id) {
        Route route = load(id);
        List<AdminViews.AdminScheduleRowView> schedules = scheduleRepository
                .findByRouteIdOrderByDepartTimeAsc(id).stream()
                .map(s -> AdminViews.AdminScheduleRowView.of(s, route.getRouteName(), route.getStartPort(),
                        route.getEndPort(), null, 0, 0))
                .toList();
        return AdminViews.RouteDetailView.from(route, schedules);
    }

    /**
     * 新增航线。
     */
    @Transactional
    public Route create(AdminForms.RouteForm form) {
        if (routeRepository.existsByRouteName(form.routeName().trim())) {
            throw new BizException("航线名称已存在", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        Route route = new Route();
        apply(route, form);
        return routeRepository.save(route);
    }

    /**
     * 编辑航线。已有班次时不允许改航程时长。
     */
    @Transactional
    public Route update(Long id, AdminForms.RouteForm form) {
        Route route = load(id);
        String newName = form.routeName().trim();
        if (!newName.equals(route.getRouteName())
                && routeRepository.existsByRouteNameAndIdNot(newName, id)) {
            throw new BizException("航线名称已存在", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if (form.durationMin() != null
                && !form.durationMin().equals(route.getDurationMin())
                && scheduleRepository.existsByRouteId(id)) {
            throw new BizException("该航线已有班次，不能修改航程时长", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        apply(route, form);
        return routeRepository.save(route);
    }

    /**
     * 删除航线：先校验前置条件。
     */
    @Transactional
    public void delete(Long id) {
        Route route = load(id);
        if (scheduleRepository.countByRouteIdAndStatusIn(id, UNFINISHED_STATUSES) > 0) {
            throw new BizException("该航线还有未完成班次，不能删除", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if (hasAnySchedule(id)) {
            throw new BizException("该航线已有历史班次，请改为停运", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        routeRepository.delete(route);
    }

    private boolean hasAnySchedule(Long routeId) {
        return scheduleRepository.existsByRouteId(routeId);
    }

    private Route load(Long id) {
        return routeRepository.findById(id)
                .orElseThrow(() -> new BizException("航线不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
    }

    private void apply(Route route, AdminForms.RouteForm form) {
        route.setRouteName(form.routeName().trim());
        route.setStartPort(form.startPort().trim());
        route.setEndPort(form.endPort().trim());
        if (form.durationMin() != null) {
            route.setDurationMin(form.durationMin());
        }
        route.setDescription(blankToNull(form.description()));
        if (form.status() != null && !form.status().isBlank()) {
            route.setStatus(form.status());
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
