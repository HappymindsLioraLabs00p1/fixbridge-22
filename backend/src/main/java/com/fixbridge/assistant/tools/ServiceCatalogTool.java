package com.fixbridge.assistant.tools;

import com.fixbridge.assistant.AssistantTool;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.catalog.CatalogService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * "What do you fix, and roughly what does it cost?" — the bookable trades with their typical price
 * range, contractor availability and rating.
 *
 * <p>The figures come from {@link CatalogService}, the same source the public catalogue page uses, so
 * the assistant cannot quote a number the site contradicts. Nothing here is customer-specific, but the
 * tool still takes the {@link AuthUser} for a uniform contract — and because a later change that
 * filters by the customer's service area should not have to alter the interface.
 */
@Component
public class ServiceCatalogTool implements AssistantTool {

    private final CatalogService catalog;

    public ServiceCatalogTool(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "list_services";
    }

    @Override
    public String description() {
        return "List the repair trades FixBridge covers, with typical price ranges and availability. "
                + "Use this before quoting any price. Takes no arguments.";
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
        return catalog.browse();
    }
}
