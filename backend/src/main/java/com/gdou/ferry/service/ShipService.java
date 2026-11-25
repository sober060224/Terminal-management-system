package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.dto.AdminForms;
import com.gdou.ferry.dto.AdminViews;
import com.gdou.ferry.entity.Route;
import com.gdou.ferry.entity.Schedule;
import com.gdou.ferry.entity.Ship;
import com.gdou.ferry.repository.RouteRepository;
import com.gdou.ferry.repository.ScheduleRepository;
import com.gdou.ferry.repository.SeatRepository;
import com.gdou.ferry.repository.ShipRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模块二·功能2：船舶管理。
 *
 * <p>前置校验与 {@link RouteService} 同构（需求 2.3-4）：
 * 有未完成班次不许删；只有历史班次也不许物理删（外键会拦），提示改为停用；
 * 已有班次时不许改总座位数（改了就和既有班次的座位表对不上）。
 */
@Service
public class ShipService {

    /** 船舶类型的合法取值（需求 2.2 功能2）。 */
    static final List<String> SHIP_TYPES = List.of("普通船", "高速船", "豪华船");

    /** 船舶状态的合法取值。 */
    static final List<String> SHIP_STATUSES = List.of("正常", "维修", "停用");

    private final ShipRepository shipRepository;
    private final ScheduleRepository scheduleRepository;

    public ShipService(ShipRepository shipRepository, ScheduleRepository scheduleRepository) {
        this.shipRepository = shipRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional(readOnly = true)
    public AdminViews.PageResult<AdminViews.ShipView> search(String name, int page, int size) {
        Page<Ship> result = shipRepository.search(blankToNull(name),
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "id")));
        return AdminViews.PageResult.of(result.map(AdminViews.ShipView::from));
    }

    @Transactional(readOnly = true)
    public AdminViews.ShipView detail(Long id) {
        return AdminViews.ShipView.from(load(id));
    }

    @Transactional
    public Ship create(AdminForms.ShipForm form) {
        if (shipRepository.existsByShipName(form.shipName().trim())) {
            throw new BizException("船舶名称已存在", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        Ship ship = new Ship();
        apply(ship, form);
        return shipRepository.save(ship);
    }

    @Transactional
    public Ship update(Long id, AdminForms.ShipForm form) {
        Ship ship = load(id);
        String newName = form.shipName().trim();
        if (!newName.equals(ship.getShipName()) && shipRepository.existsByShipNameAndIdNot(newName, id)) {
            throw new BizException("船舶名称已存在", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if (form.totalSeats() != null
                && !form.totalSeats().equals(ship.getTotalSeats())
                && hasAnySchedule(id)) {
            throw new BizException("该船舶已有班次，不能修改总座位数", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        apply(ship, form);
        return shipRepository.save(ship);
    }

    @Transactional
    public void delete(Long id) {
        Ship ship = load(id);
        if (scheduleRepository.countByShipIdAndStatusIn(id, RouteService.UNFINISHED_STATUSES) > 0) {
            throw new BizException("该船舶还有未完成班次，不能删除", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        if (hasAnySchedule(id)) {
            throw new BizException("该船舶已有历史班次，请将状态改为停用", HttpStatus.CONFLICT, ErrorCodes.CONFLICT);
        }
        shipRepository.delete(ship);
    }

    private boolean hasAnySchedule(Long shipId) {
        return scheduleRepository.existsByShipId(shipId);
    }

    private Ship load(Long id) {
        return shipRepository.findById(id)
                .orElseThrow(() -> new BizException("船舶不存在", HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND));
    }

    private void apply(Ship ship, AdminForms.ShipForm form) {
        ship.setShipName(form.shipName().trim());
        if (form.totalSeats() != null) {
            ship.setTotalSeats(form.totalSeats());
        }
        if (form.shipType() != null && !form.shipType().isBlank()) {
            ship.setShipType(form.shipType());
        }
        ship.setDescription(blankToNull(form.description()));
        if (form.status() != null && !form.status().isBlank()) {
            ship.setStatus(form.status());
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
