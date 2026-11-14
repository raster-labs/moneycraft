package com.raster.moneycraft.reporting.web;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.reporting.dto.ReportingDtos;
import com.raster.moneycraft.reporting.service.SmartFindService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for global TallyPrime Rel 7.2 SmartFind (Alt+F / Ctrl+F).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/search")
public class SmartFindController {

    private final SmartFindService smartFindService;

    public SmartFindController(SmartFindService smartFindService) {
        this.smartFindService = smartFindService;
    }

    @GetMapping("/smartfind")
    public ReportingDtos.SmartFindResponseDto smartFind(
        @AuthenticationPrincipal MoneyCraftPrincipal principal,
        @PathVariable UUID companyId,
        @RequestParam(name = "q", defaultValue = "") String query,
        @RequestParam(defaultValue = "ACTIVE") String scope,
        @RequestParam(defaultValue = "30") Integer limit
    ) {
        return smartFindService.search(principal, companyId, query, scope, limit);
    }
}
