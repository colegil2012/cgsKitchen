package com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.OptionGroup;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OptionGroupRepository extends MongoRepository<OptionGroup, String> {
}