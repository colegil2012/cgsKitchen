package com.celtech.solutions.cgsKitchen.repositories;

import com.celtech.solutions.cgsKitchen.models.MenuItem;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MenuItemRepository extends MongoRepository<MenuItem, String> {
    List<MenuItem> findByClientId(String clientId, Sort sort);
    List<MenuItem> findByClientIdAndAvailableTrue(String clientId, Sort sort);
}
