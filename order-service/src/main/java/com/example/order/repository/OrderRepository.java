package com.example.order.repository;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByReleasePendingTrue();

    List<Order> findByStatus(OrderStatus status);
}
