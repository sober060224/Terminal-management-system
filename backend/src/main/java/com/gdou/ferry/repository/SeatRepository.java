package com.gdou.ferry.repository;

import com.gdou.ferry.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScheduleIdOrderByIdAsc(Long scheduleId);

    Optional<Seat> findByScheduleIdAndSeatNo(Long scheduleId, String seatNo);

    long countByScheduleIdAndSeatStatus(Long scheduleId, String seatStatus);
}
