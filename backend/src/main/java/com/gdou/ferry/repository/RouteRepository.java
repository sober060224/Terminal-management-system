package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Route;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouteRepository extends JpaRepository<Route, Long> {

    /**
     * 航线列表（需求 2.2 功能1）：按名称 / 出发港 / 到达港模糊过滤。
     * 三个条件都传时按 AND 组合；不传的条件不参与筛选。
     */
    @Query("select r from Route r where "
            + "(:name is null or r.routeName like concat('%', :name, '%')) and "
            + "(:startPort is null or r.startPort like concat('%', :startPort, '%')) and "
            + "(:endPort is null or r.endPort like concat('%', :endPort, '%'))")
    Page<Route> search(@Param("name") String name,
                       @Param("startPort") String startPort,
                       @Param("endPort") String endPort,
                       Pageable pageable);

    /** 航线名唯一：新增与改名都要查。 */
    boolean existsByRouteName(String routeName);

    /** 改名时排除自身：同一条航线不会被自己撞名。 */
    @Query("select count(r) > 0 from Route r where r.routeName = :routeName and r.id <> :excludeId")
    boolean existsByRouteNameAndIdNot(@Param("routeName") String routeName, @Param("excludeId") Long excludeId);
}
