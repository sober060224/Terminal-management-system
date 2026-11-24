package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Ship;

/**
 * 模块二提供给检票、报表等模块的只读船舶引用。
 */
public record ShipReference(
        Long id,
        String shipName,
        Integer totalSeats,
        String shipType,
        String status) {

    public static ShipReference from(Ship ship) {
        return new ShipReference(
                ship.getId(),
                ship.getShipName(),
                ship.getTotalSeats(),
                ship.getShipType(),
                ship.getStatus()
        );
    }
}
