package com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.OptionChoice;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OptionChoiceRepository extends MongoRepository<OptionChoice, String> {
    List<OptionChoice> findByTag(String tag);
    List<OptionChoice> findByAvailableFalse();
}