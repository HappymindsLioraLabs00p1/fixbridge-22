package com.fixbridge.assistant.tools;

import com.fixbridge.assistant.AssistantTool;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.property.Property;
import com.fixbridge.property.PropertyRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * "Which of my addresses?" — the customer's own properties.
 *
 * <p>Exists so that reporting an issue can name a real address the customer recognises instead of a
 * bare id. Scoped by owner at the query, not filtered afterwards.
 */
@Component
public class ListMyPropertiesTool implements AssistantTool {

    private final PropertyRepository properties;

    public ListMyPropertiesTool(PropertyRepository properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "list_my_properties";
    }

    @Override
    public String description() {
        return "List the customer's own properties, so an issue can be reported against the right one. "
                + "Takes no arguments.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public Object execute(AuthUser user, Map<String, Object> arguments) {
        return properties.findByOwnerId(user.id()).stream()
                .map(p -> Map.of(
                        "property_id", p.getId().toString(),
                        "label", describe(p)))
                .toList();
    }

    /** A human-recognisable name — the label if there is one, otherwise the street line. */
    static String describe(Property p) {
        if (p.getLabel() != null && !p.getLabel().isBlank()) {
            return p.getLabel();
        }
        List<String> parts = java.util.stream.Stream.of(p.getLine1(), p.getCity())
                .filter(s -> s != null && !s.isBlank())
                .toList();
        return parts.isEmpty() ? "Your property" : String.join(", ", parts);
    }
}
