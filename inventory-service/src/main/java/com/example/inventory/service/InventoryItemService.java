package com.example.inventory.service;

import com.example.inventory.common.NotFoundException;
import com.example.inventory.domain.InventoryItem;
import com.example.inventory.dto.InventoryItemRequest;
import com.example.inventory.dto.InventoryItemResponse;
import com.example.inventory.repository.InventoryItemRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryItemService {

    private final InventoryItemRepository repository;

    public InventoryItemService(InventoryItemRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public InventoryItemResponse create(InventoryItemRequest r) {
        InventoryItem e = new InventoryItem();
        apply(e, r);
        return InventoryItemResponse.from(repository.save(e));
    }

    public InventoryItemResponse get(Long id) {
        return InventoryItemResponse.from(find(id));
    }

    public List<InventoryItemResponse> list(int page, int size) {
        return repository.findAll(PageRequest.of(page, size)).map(InventoryItemResponse::from).getContent();
    }

    @Transactional
    public InventoryItemResponse update(Long id, InventoryItemRequest r) {
        InventoryItem e = find(id);
        apply(e, r);
        return InventoryItemResponse.from(e);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(find(id));
    }

    private InventoryItem find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("InventoryItem", id));
    }

    private static void apply(InventoryItem e, InventoryItemRequest r) {
        e.setProductId(r.productId());
        e.setProductName(r.productName());
        e.setAvailableQuantity(r.availableQuantity());
        e.setUnitPrice(r.unitPrice());
    }
}
