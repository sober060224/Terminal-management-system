package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Ship;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipRepository extends JpaRepository<Ship, Long> {

    /** 船舶列表（需求 2.2 功能2）：按船名模糊过滤。 */
    @Query("select s from Ship s where (:name is null or s.shipName like concat('%', :name, '%'))")
    Page<Ship> search(@Param("name") String name, Pageable pageable);

    boolean existsByShipName(String shipName);

    @Query("select count(s) > 0 from Ship s where s.shipName = :shipName and s.id <> :excludeId")
    boolean existsByShipNameAndIdNot(@Param("shipName") String shipName, @Param("excludeId") Long excludeId);
}
