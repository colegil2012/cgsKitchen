package com.celtech.solutions.cgsKitchen.controllers.admin;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.OptionChoice;
import com.celtech.solutions.cgsKitchen.models.storefront.menu.meta.OptionGroup;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.OptionChoiceRepository;
import com.celtech.solutions.cgsKitchen.repositories.storefront.menu.meta.OptionGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Admin pages for option groups and choices.
 *
 * <p>The /admin/options index is a small hub linking to two sibling
 * areas: /admin/options/choice and /admin/options/group. Each has its
 * own list + edit pages so the screens stay focused.
 */
@Controller
@RequestMapping("/admin/options")
@RequiredArgsConstructor
public class AdminOptionsController {

    private final OptionGroupRepository groups;
    private final OptionChoiceRepository choices;

    // -------------------- Index hub --------------------

    @GetMapping
    public String index(Model model) {
        model.addAttribute("groupCount", groups.count());
        model.addAttribute("choiceCount", choices.count());
        model.addAttribute("eightySixedChoices", choices.findByAvailableFalse().size());
        return "admin/options/index";
    }

    // -------------------- Choices --------------------

    @GetMapping("/choice")
    public String choiceList(Model model) {
        model.addAttribute("choices", choices.findAll());
        return "admin/options/choice/list";
    }

    @GetMapping("/choice/new")
    public String newChoice(Model model) {
        model.addAttribute("choice", OptionChoice.builder().available(true).build());
        return "admin/options/choice/edit";
    }

    @GetMapping("/choice/{id}")
    public String editChoice(@PathVariable String id, Model model,
                             RedirectAttributes redirect) {
        var c = choices.findById(id).orElse(null);
        if (c == null) {
            redirect.addFlashAttribute("error", "Choice not found.");
            return "redirect:/admin/options/choice";
        }
        model.addAttribute("choice", c);
        return "admin/options/choice/edit";
    }

    @PostMapping("/choice/save")
    public String saveChoice(
            @RequestParam(required = false) String id,
            @RequestParam String label,
            @RequestParam(defaultValue = "0") long priceDeltaCents,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean available,
            @RequestParam(required = false) String unavailableReason,
            RedirectAttributes redirect
    ) {
        OptionChoice c;
        if (id == null || id.isBlank()) {
            String slug = label.trim().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-|-$", "");
            c = choices.findById(slug).orElseGet(() -> OptionChoice.builder().id(slug).build());
        } else {
            c = choices.findById(id).orElseThrow();
        }
        c.setLabel(label.trim());
        c.setPriceDeltaCents(priceDeltaCents);
        c.setTag(tag == null ? null : tag.trim());
        c.setAvailable(available);
        c.setUnavailableReason(available ? null
                : (unavailableReason == null || unavailableReason.isBlank() ? null : unavailableReason.trim()));
        choices.save(c);
        redirect.addFlashAttribute("notice", "Saved \"" + c.getLabel() + "\".");
        return "redirect:/admin/options/choice";
    }

    @PostMapping("/choice/{id}/toggle-availability")
    public String toggleChoice(@PathVariable String id,
                               @RequestParam(required = false) String reason,
                               RedirectAttributes redirect) {
        var c = choices.findById(id).orElse(null);
        if (c == null) {
            redirect.addFlashAttribute("error", "Choice not found.");
            return "redirect:/admin/options/choice";
        }
        boolean nowAvailable = !c.isAvailable();
        c.setAvailable(nowAvailable);
        c.setUnavailableReason(nowAvailable ? null
                : (reason == null || reason.isBlank() ? "Out" : reason.trim()));
        choices.save(c);
        redirect.addFlashAttribute("notice",
                c.getLabel() + (nowAvailable ? " — restored." : " — 86'd."));
        return "redirect:/admin/options/choice";
    }

    @PostMapping("/choice/{id}/delete")
    public String deleteChoice(@PathVariable String id, RedirectAttributes redirect) {
        choices.deleteById(id);
        redirect.addFlashAttribute("notice", "Choice removed.");
        return "redirect:/admin/options/choice";
    }

    // -------------------- Groups --------------------

    @GetMapping("/group")
    public String groupList(Model model) {
        model.addAttribute("groups", groups.findAll());
        return "admin/options/group/list";
    }

    @GetMapping("/group/new")
    public String newGroup(Model model) {
        model.addAttribute("group",
                OptionGroup.builder()
                        .selectionType(OptionGroup.SelectionType.SINGLE)
                        .available(true)
                        .build());
        model.addAttribute("allChoices", choices.findAll());
        return "admin/options/group/edit";
    }

    @GetMapping("/group/{id}")
    public String editGroup(@PathVariable String id, Model model,
                            RedirectAttributes redirect) {
        var g = groups.findById(id).orElse(null);
        if (g == null) {
            redirect.addFlashAttribute("error", "Group not found.");
            return "redirect:/admin/options/group";
        }
        model.addAttribute("group", g);
        model.addAttribute("allChoices", choices.findAll());
        return "admin/options/group/edit";
    }

    @PostMapping("/group/save")
    public String saveGroup(
            @RequestParam(required = false) String id,
            @RequestParam String label,
            @RequestParam OptionGroup.SelectionType selectionType,
            @RequestParam(defaultValue = "false") boolean required,
            @RequestParam(defaultValue = "0") int maxSelections,
            @RequestParam(defaultValue = "true") boolean available,
            @RequestParam(required = false) List<String> choiceIds,
            @RequestParam(required = false) String defaultChoiceId,
            RedirectAttributes redirect
    ) {
        OptionGroup g;
        if (id == null || id.isBlank()) {
            String slug = label.trim().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-|-$", "");
            g = groups.findById(slug).orElseGet(() -> OptionGroup.builder().id(slug).build());
        } else {
            g = groups.findById(id).orElseThrow();
        }
        g.setLabel(label.trim());
        g.setSelectionType(selectionType);
        g.setRequired(required);
        g.setMaxSelections(maxSelections);
        g.setAvailable(available);
        g.setChoiceIds(choiceIds == null ? List.of() : choiceIds);
        g.setDefaultChoiceId(defaultChoiceId == null || defaultChoiceId.isBlank() ? null : defaultChoiceId);
        groups.save(g);
        redirect.addFlashAttribute("notice", "Saved \"" + g.getLabel() + "\".");
        return "redirect:/admin/options/group";
    }

    @PostMapping("/group/{id}/toggle-availability")
    public String toggleGroup(@PathVariable String id, RedirectAttributes redirect) {
        var g = groups.findById(id).orElse(null);
        if (g == null) {
            redirect.addFlashAttribute("error", "Group not found.");
            return "redirect:/admin/options/group";
        }
        g.setAvailable(!g.isAvailable());
        groups.save(g);
        redirect.addFlashAttribute("notice",
                g.getLabel() + (g.isAvailable() ? " — restored." : " — disabled."));
        return "redirect:/admin/options/group";
    }

    @PostMapping("/group/{id}/delete")
    public String deleteGroup(@PathVariable String id, RedirectAttributes redirect) {
        groups.deleteById(id);
        redirect.addFlashAttribute("notice", "Group removed.");
        return "redirect:/admin/options/group";
    }
}