package com.fixbridge.job;

import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.job.dto.ChangeOrderDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('customer')")
public class ChangeOrderController {

    private final ChangeOrderService changeOrderService;

    public ChangeOrderController(ChangeOrderService changeOrderService) {
        this.changeOrderService = changeOrderService;
    }

    /** Customer-safe change orders for a job (added retail only — never the net). */
    @GetMapping("/change-orders")
    public List<ChangeOrderDtos.CustomerView> forJob(@RequestParam UUID jobId) {
        return changeOrderService.listForCustomer(SecurityUtil.currentUser(), jobId);
    }

    /** Customer approves the retail change order so work can resume. */
    @PostMapping("/change-orders/{changeOrderId}/approve")
    public ChangeOrderDtos.CustomerView approve(@PathVariable UUID changeOrderId) {
        return changeOrderService.approve(SecurityUtil.currentUser(), changeOrderId);
    }
}
