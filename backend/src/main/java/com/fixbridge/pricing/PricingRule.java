package com.fixbridge.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** Admin-editable pricing rule. The pricing engine reads these — the AI never sets price. */
@Entity
@Table(name = "pricing_rules")
@Getter
@Setter
@NoArgsConstructor
public class PricingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String scope = "global";

    @Column(name = "trade_code")
    private String tradeCode;

    private String region;

    @Column(name = "target_gross_margin", nullable = false)
    private BigDecimal targetGrossMargin = new BigDecimal("0.2500");

    @Column(name = "minimum_gross_profit_cents", nullable = false)
    private long minimumGrossProfitCents = 7500;

    @Column(name = "fixed_platform_cost_cents", nullable = false)
    private long fixedPlatformCostCents = 7500;

    @Column(name = "risk_reserve_cents", nullable = false)
    private long riskReserveCents = 5000;

    @Column(name = "variable_payment_fee_rate", nullable = false)
    private BigDecimal variablePaymentFeeRate = new BigDecimal("0.0290");

    @Column(name = "fixed_payment_fee_cents", nullable = false)
    private long fixedPaymentFeeCents = 30;

    @Column(name = "location_factor", nullable = false)
    private BigDecimal locationFactor = BigDecimal.ONE;

    @Column(nullable = false)
    private boolean active = true;
}
