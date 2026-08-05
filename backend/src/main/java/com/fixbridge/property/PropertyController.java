package com.fixbridge.property;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.auth.SecurityUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyRepository properties;

    public PropertyController(PropertyRepository properties) {
        this.properties = properties;
    }

    public record CreatePropertyRequest(
            String label,
            @NotBlank String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String country
    ) {}

    public record PropertyView(UUID id, String label, String line1, String city, String state,
                               String postalCode, Instant createdAt) {}

    @PostMapping
    public PropertyView create(@Valid @RequestBody CreatePropertyRequest req) {
        AuthUser user = SecurityUtil.currentUser();
        Property p = new Property();
        p.setOwnerId(user.id());
        p.setLabel(req.label());
        p.setLine1(req.line1());
        p.setLine2(req.line2());
        p.setCity(req.city());
        p.setState(req.state());
        p.setPostalCode(req.postalCode());
        if (req.country() != null && !req.country().isBlank()) {
            p.setCountry(req.country());
        }
        p = properties.save(p);
        return toView(p);
    }

    @GetMapping
    public List<PropertyView> list() {
        AuthUser user = SecurityUtil.currentUser();
        return properties.findByOwnerId(user.id()).stream().map(PropertyController::toView).toList();
    }

    private static PropertyView toView(Property p) {
        return new PropertyView(p.getId(), p.getLabel(), p.getLine1(), p.getCity(), p.getState(),
                p.getPostalCode(), p.getCreatedAt());
    }
}
