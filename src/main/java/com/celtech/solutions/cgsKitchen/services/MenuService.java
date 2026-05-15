package com.celtech.solutions.cgsKitchen.services;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.MenuItem;
import com.celtech.solutions.cgsKitchen.repositories.MenuItemRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuItemRepository menuItems;
    private final AppProperties props;

    /**
     * On first boot, if the menu is empty, seed it with the demo items.
     * Idempotent — safe to leave on in production (it only runs when the
     * collection is empty).
     */
    @PostConstruct
    void seedIfEmpty() {
        if (!menuItems.findByClientId(props.clientId(), Sort.unsorted()).isEmpty()) {
            return;
        }
        log.info("Menu collection empty for client={}, seeding demo data",
                 props.clientId());
        menuItems.saveAll(List.of(
            item("taco-al-pastor", "Al Pastor",
                 "Marinated pork shoulder, grilled pineapple, cilantro, salsa verde.",
                 450, "tacos", "Most loved", 1),
            item("taco-carnitas", "Carnitas",
                 "Slow-braised pork, pickled red onion, salsa roja, queso fresco.",
                 450, "tacos", null, 2),
            item("taco-barbacoa", "Barbacoa",
                 "Beef cheek, slow-cooked overnight. Salsa morita, onion, cilantro.",
                 500, "tacos", "Limited", 3),
            item("taco-pollo", "Pollo Asado",
                 "Citrus-marinated chicken thigh, avocado salsa, queso fresco.",
                 450, "tacos", null, 4),
            item("taco-veggie", "Hongos",
                 "Roasted oyster mushrooms, blistered poblano, cashew crema.",
                 450, "tacos", null, 5),
            item("plate-platter", "Three-Taco Plate",
                 "Pick three. Comes with rice, refried beans, two house salsas.",
                 1400, "plates", null, 10),
            item("side-elote", "Elote",
                 "Charred corn, lime crema, cotija, tajín.",
                 500, "sides", null, 20),
            item("side-chips", "Chips & Salsa Trio",
                 "Salsa verde, salsa roja, salsa morita.",
                 600, "sides", null, 21),
            item("drink-horchata", "Horchata",
                 "Cinnamon rice milk, made fresh daily.",
                 400, "drinks", null, 30),
            item("drink-jamaica", "Agua de Jamaica",
                 "Hibiscus, hint of lime.",
                 400, "drinks", null, 31),
            item("drink-jarritos", "Jarritos",
                 "Tamarind, mandarin, or lime.",
                 300, "drinks", null, 32)
        ));
    }

    public List<MenuItem> findAll() {
        return menuItems.findByClientId(props.clientId(),
                Sort.by("sortOrder", "name"));
    }

    public List<MenuItem> findAvailable() {
        return menuItems.findByClientIdAndAvailableTrue(props.clientId(),
                Sort.by("sortOrder", "name"));
    }

    public Optional<MenuItem> findById(String id) {
        return menuItems.findById(id)
                .filter(m -> m.getClientId().equals(props.clientId()));
    }

    /**
     * Group menu items by category for templating, preserving sort order.
     */
    public Map<String, List<MenuItem>> findGroupedByCategory() {
        return findAvailable().stream()
                .collect(Collectors.groupingBy(
                        MenuItem::getCategory,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    private MenuItem item(String id, String name, String desc, long cents,
                          String cat, String badge, int sort) {
        return MenuItem.builder()
                .id(id)
                .clientId(props.clientId())
                .name(name)
                .description(desc)
                .priceCents(cents)
                .category(cat)
                .badge(badge)
                .available(true)
                .sortOrder(sort)
                .build();
    }
}
