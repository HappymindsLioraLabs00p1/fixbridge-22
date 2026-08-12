package com.fixbridge.catalog;

import com.fixbridge.catalog.dto.CatalogDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The service catalogue.
 *
 * <p>Deliberately readable without signing in. Someone deciding whether to use FixBridge at all
 * should be able to see what it does and roughly what it costs first — requiring an account to
 * view a price list is the fastest way to lose them.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<CatalogDtos.ServiceCard> browse() {
        return catalog.browse();
    }
}
