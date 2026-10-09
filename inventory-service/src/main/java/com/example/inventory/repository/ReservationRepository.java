package com.example.inventory.repository;

import com.example.inventory.domain.Reservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByOrderId(Long orderId);

    /** Atomic RESERVED to RELEASED transition; returns 0 if already released or absent. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Reservation r set r.status = com.example.inventory.domain.ReservationStatus.RELEASED "
            + "where r.orderId = :orderId and r.status = com.example.inventory.domain.ReservationStatus.RESERVED")
    int markReleased(@Param("orderId") Long orderId);
}
