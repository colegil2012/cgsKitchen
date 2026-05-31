package com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta;

import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.Category;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CategoryRepository extends MongoRepository<Category, String> {
    List<Category> findAll(Sort sort);
}