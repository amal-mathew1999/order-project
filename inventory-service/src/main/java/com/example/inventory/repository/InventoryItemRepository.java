package com.example.inventory.repository;

import com.example.inventory.domain.InventoryItem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findByProductId(Long productId);

    /** Atomic conditional decrement; returns 0 rows when stock is insufficient. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InventoryItem i set i.availableQuantity = i.availableQuantity - :qty "
            + "where i.productId = :productId and i.availableQuantity >= :qty")
    int reserve(@Param("productId") Long productId, @Param("qty") int qty);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InventoryItem i set i.availableQuantity = i.availableQuantity + :qty "
            + "where i.productId = :productId")
    int release(@Param("productId") Long productId, @Param("qty") int qty);
}
