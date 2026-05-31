
package com.celtech.solutions.cgsKitchen.repositories.user;

import com.celtech.solutions.cgsKitchen.models.user.Address;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends MongoRepository<Address, String> {
    List<Address> findByUserIdOrderByPrimaryDescUpdatedAtDesc(String userId);
    Optional<Address> findByUserIdAndPrimaryTrue(String userId);
}