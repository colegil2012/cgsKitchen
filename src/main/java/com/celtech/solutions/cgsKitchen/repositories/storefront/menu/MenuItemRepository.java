package com.celtech.solutions.cgsKitchen.repositories.storefront.menu;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.MenuItem;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MenuItemRepository extends MongoRepository<MenuItem, String> {
    List<MenuItem> findByAvailableTrue(Sort sort);
    List<MenuItem> findByCategoryIdAndAvailableTrue(String categoryId, Sort sort);
    List<MenuItem> findByAvailableFalse();
}