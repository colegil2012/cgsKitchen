package com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.Badge;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BadgeRepository extends MongoRepository<Badge, String> {
}