package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Schedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    List<Schedule> findByStatusOrderByDepartTimeAsc(String status);

    /**
     * ADR-0002：班次写操作前先悲观锁班次行。调用方须处于事务中。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.id = :id")
    Optional<Schedule> findByIdForUpdate(@Param("id") Long id);

    /**
     * 班次列表（需求 2.2 功能3）：按航线 / 船舶 / 日期区间 / 状态过滤。
     * 日期比较用 {@code function('date', s.departTime)}，即按发船**日期**过滤（两端都含）。
     */
    @Query("select s from Schedule s where "
            + "(:routeId is null or s.routeId = :routeId) and "
            + "(:shipId is null or s.shipId = :shipId) and "
            + "(:status is null or s.status = :status) and "
            + "(:dateFrom is null or function('date', s.departTime) >= :dateFrom) and "
            + "(:dateTo is null or function('date', s.departTime) <= :dateTo)")
    Page<Schedule> search(@Param("routeId") Long routeId,
                           @Param("shipId") Long shipId,
                           @Param("status") String status,
                           @Param("dateFrom") LocalDateTime dateFrom,
                           @Param("dateTo") LocalDateTime dateTo,
                           Pageable pageable);

    /**
     * 同船时间区间重叠检查（需求 2.3-1）。
     *
     * <p>数据库的 {@code uk_ship_depart (ship_id, depart_time)} 只能拦住「完全相同时刻」，
     * 区间重叠表达不了（PLAN.md §6 已登记为已知风险），所以在应用层用
     * {@code NOT (arriveTime <= depart AND departTime >= arrive)} 判定，
     * 即两个半开区间 [depart, arrive) 有交集就冲突。excludeId 用于改班次时排除自身。
     */
    @Query("select count(s) > 0 from Schedule s where s.shipId = :shipId "
            + "and s.id <> :excludeId "
            + "and not (s.arriveTime <= :departTime or s.departTime >= :arriveTime)")
    boolean existsOverlapping(@Param("shipId") Long shipId,
                              @Param("departTime") LocalDateTime departTime,
                              @Param("arriveTime") LocalDateTime arriveTime,
                              @Param("excludeId") Long excludeId);

    /** 某航线下处于给定状态的班次数：删除航线 / 改航程时长的守卫。 */
    long countByRouteIdAndStatusIn(Long routeId, Collection<String> statuses);

    /** 某船舶下处于给定状态的班次数：删除船舶 / 改座位数的守卫。 */
    long countByShipIdAndStatusIn(Long shipId, Collection<String> statuses);

    /** 某航线下的全部班次（航线详情用），按发船时间升序。 */
    List<Schedule> findByRouteIdOrderByDepartTimeAsc(Long routeId);

    /** 该航线下是否有过任何班次（含历史）：删除航线、改航程时长的守卫。 */
    boolean existsByRouteId(Long routeId);

    /** 该船舶下是否有过任何班次（含历史）：删除船舶、改座位数的守卫。 */
    boolean existsByShipId(Long shipId);

    /** 状态推进用：待发布 → 售票中。条件更新保证并发下只有一方改到行。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Schedule s set s.status = '售票中' where s.id = :id and s.status = '待发布'")
    int markOnSale(@Param("id") Long id);

    /** 状态推进用：售票中 → 已发船（发船时间已到）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Schedule s set s.status = '已发船' "
            + "where s.id = :id and s.status = '售票中' and s.departTime <= :now")
    int markDeparted(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 状态推进用：已发船 → 已完成（到达时间已到）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Schedule s set s.status = '已完成' "
            + "where s.id = :id and s.status = '已发船' and s.arriveTime <= :now")
    int markCompleted(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 定时任务用：售票中且发船时间已过的班次。 */
    List<Schedule> findByStatusAndDepartTimeLessThanEqual(String status, LocalDateTime time);

    /** 定时任务用：已发船且到达时间已过的班次。 */
    List<Schedule> findByStatusAndArriveTimeLessThanEqual(String status, LocalDateTime time);
}
