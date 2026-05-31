package com.celtech.solutions.cgsKitchen.repositories.user;

import com.celtech.solutions.cgsKitchen.models.user.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByStripeCustomerId(String stripeCustomerId);
    boolean existsByEmailIgnoreCase(String email);
}