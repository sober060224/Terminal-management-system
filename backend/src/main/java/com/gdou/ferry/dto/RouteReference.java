package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Route;

/**
 * 模块二提供给检票、报表等模块的只读航线引用。
 */
public record RouteReference(
        Long id,
        String routeName,
        String startPort,
        String endPort,
        String status) {

    public static RouteReference from(Route route) {
        return new RouteReference(
                route.getId(),
                route.getRouteName(),
                route.getStartPort(),
                route.getEndPort(),
                route.getStatus()
        );
    }
}
