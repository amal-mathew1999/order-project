package com.example.order.repository;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByReleasePendingTrue();

    List<Order> findByStatus(OrderStatus status);

    /** PENDING orders older than the cutoff: the flow died mid-way (crash, failed status write). */
    @Query("select o from Order o where o.status = com.example.order.domain.OrderStatus.PENDING"
            + " and (o.createdAt is null or o.createdAt < :cutoff)")
    List<Order> findStalePending(@Param("cutoff") Instant cutoff);
}
