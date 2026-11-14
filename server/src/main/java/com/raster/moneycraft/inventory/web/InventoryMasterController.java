package com.raster.moneycraft.inventory.web;

import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.inventory.dto.InventoryMasterDtos;
import com.raster.moneycraft.inventory.service.InventoryMasterService;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Phase 4 Inventory Masters:
 * UOMs, Godowns, Stock Groups, Stock Categories, Stock Items, Batches, Price Lists, and BOMs.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InventoryMasterController {

    private final InventoryMasterService inventoryMasterService;
    private final RbacAuthorizationService rbacGuard;

    public InventoryMasterController(
        InventoryMasterService inventoryMasterService,
        RbacAuthorizationService rbacGuard
    ) {
        this.inventoryMasterService = inventoryMasterService;
        this.rbacGuard = rbacGuard;
    }

    // -------------------------------------------------------------------------
    // 1. Units of Measure (/uoms)
    // -------------------------------------------------------------------------

    @GetMapping("/uoms")
    public List<InventoryMasterDtos.UomDto> listUoms(@PathVariable("companyId") UUID companyId) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.listUoms(principal, companyId);
    }

    @PostMapping("/uoms")
    public ResponseEntity<InventoryMasterDtos.UomDto> createUom(
        @PathVariable("companyId") UUID companyId,
        @RequestBody InventoryMasterDtos.UpsertUomRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.UomDto created = inventoryMasterService.createUom(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/uoms/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/uoms/{uomId}")
    public ResponseEntity<InventoryMasterDtos.UomDto> getUom(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("uomId") UUID uomId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.UomDto uom = inventoryMasterService.getUom(principal, companyId, uomId);
        return ResponseEntity.ok().eTag("\"v" + uom.versionNo() + "\"").body(uom);
    }

    @PutMapping("/uoms/{uomId}")
    public ResponseEntity<InventoryMasterDtos.UomDto> updateUom(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("uomId") UUID uomId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestBody InventoryMasterDtos.UpsertUomRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.UomDto updated = inventoryMasterService.updateUom(
            actor, companyId, uomId, ifMatch, payload
        );
        return ResponseEntity.ok().eTag("\"v" + updated.versionNo() + "\"").body(updated);
    }

    @DeleteMapping("/uoms/{uomId}")
    public ResponseEntity<Void> deleteUom(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("uomId") UUID uomId
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        inventoryMasterService.deleteUom(actor, companyId, uomId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // 2. Godowns (/godowns)
    // -------------------------------------------------------------------------

    @GetMapping("/godowns")
    public List<InventoryMasterDtos.GodownDto> listGodowns(@PathVariable("companyId") UUID companyId) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.listGodowns(principal, companyId);
    }

    @PostMapping("/godowns")
    public ResponseEntity<InventoryMasterDtos.GodownDto> createGodown(
        @PathVariable("companyId") UUID companyId,
        @RequestBody InventoryMasterDtos.UpsertGodownRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.GodownDto created = inventoryMasterService.createGodown(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/godowns/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/godowns/{godownId}")
    public ResponseEntity<InventoryMasterDtos.GodownDto> getGodown(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("godownId") UUID godownId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.GodownDto godown = inventoryMasterService.getGodown(principal, companyId, godownId);
        return ResponseEntity.ok().eTag("\"v" + godown.versionNo() + "\"").body(godown);
    }

    @PutMapping("/godowns/{godownId}")
    public ResponseEntity<InventoryMasterDtos.GodownDto> updateGodown(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("godownId") UUID godownId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestBody InventoryMasterDtos.UpsertGodownRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.GodownDto updated = inventoryMasterService.updateGodown(
            actor, companyId, godownId, ifMatch, payload
        );
        return ResponseEntity.ok().eTag("\"v" + updated.versionNo() + "\"").body(updated);
    }

    @DeleteMapping("/godowns/{godownId}")
    public ResponseEntity<Void> deleteGodown(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("godownId") UUID godownId
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        inventoryMasterService.deleteGodown(actor, companyId, godownId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // 3. Stock Groups & Stock Categories (/stock-groups, /stock-categories)
    // -------------------------------------------------------------------------

    @GetMapping("/stock-groups")
    public List<InventoryMasterDtos.StockGroupDto> listStockGroups(@PathVariable("companyId") UUID companyId) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.listStockGroups(principal, companyId);
    }

    @PostMapping("/stock-groups")
    public ResponseEntity<InventoryMasterDtos.StockGroupDto> createStockGroup(
        @PathVariable("companyId") UUID companyId,
        @RequestBody InventoryMasterDtos.UpsertStockGroupRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockGroupDto created = inventoryMasterService.createStockGroup(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/stock-groups/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/stock-groups/{stockGroupId}")
    public ResponseEntity<InventoryMasterDtos.StockGroupDto> getStockGroup(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockGroupId") UUID stockGroupId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockGroupDto group = inventoryMasterService.getStockGroup(
            principal, companyId, stockGroupId
        );
        return ResponseEntity.ok().eTag("\"v" + group.versionNo() + "\"").body(group);
    }

    @PutMapping("/stock-groups/{stockGroupId}")
    public ResponseEntity<InventoryMasterDtos.StockGroupDto> updateStockGroup(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockGroupId") UUID stockGroupId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestBody InventoryMasterDtos.UpsertStockGroupRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockGroupDto updated = inventoryMasterService.updateStockGroup(
            actor, companyId, stockGroupId, ifMatch, payload
        );
        return ResponseEntity.ok().eTag("\"v" + updated.versionNo() + "\"").body(updated);
    }

    @DeleteMapping("/stock-groups/{stockGroupId}")
    public ResponseEntity<Void> deleteStockGroup(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockGroupId") UUID stockGroupId
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        inventoryMasterService.deleteStockGroup(actor, companyId, stockGroupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stock-categories")
    public List<InventoryMasterDtos.StockCategoryDto> listStockCategories(@PathVariable("companyId") UUID companyId) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.listStockCategories(principal, companyId);
    }

    @PostMapping("/stock-categories")
    public ResponseEntity<InventoryMasterDtos.StockCategoryDto> createStockCategory(
        @PathVariable("companyId") UUID companyId,
        @RequestBody InventoryMasterDtos.UpsertStockCategoryRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockCategoryDto created = inventoryMasterService.createStockCategory(
            actor, companyId, payload
        );
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/stock-categories/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/stock-categories/{stockCategoryId}")
    public ResponseEntity<InventoryMasterDtos.StockCategoryDto> getStockCategory(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockCategoryId") UUID stockCategoryId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockCategoryDto cat = inventoryMasterService.getStockCategory(
            principal, companyId, stockCategoryId
        );
        return ResponseEntity.ok().eTag("\"v" + cat.versionNo() + "\"").body(cat);
    }

    @PutMapping("/stock-categories/{stockCategoryId}")
    public ResponseEntity<InventoryMasterDtos.StockCategoryDto> updateStockCategory(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockCategoryId") UUID stockCategoryId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestBody InventoryMasterDtos.UpsertStockCategoryRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockCategoryDto updated = inventoryMasterService.updateStockCategory(
            actor, companyId, stockCategoryId, ifMatch, payload
        );
        return ResponseEntity.ok().eTag("\"v" + updated.versionNo() + "\"").body(updated);
    }

    @DeleteMapping("/stock-categories/{stockCategoryId}")
    public ResponseEntity<Void> deleteStockCategory(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockCategoryId") UUID stockCategoryId
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        inventoryMasterService.deleteStockCategory(actor, companyId, stockCategoryId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // 4. Stock Items & Batches (/stock-items, /stock-items/typeahead, /batches)
    // -------------------------------------------------------------------------

    @GetMapping("/stock-items")
    public List<InventoryMasterDtos.StockItemDto> listStockItems(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "stockGroupId", required = false) UUID stockGroupId,
        @RequestParam(value = "q", required = false) String q
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.listStockItems(principal, companyId, stockGroupId, q);
    }

    @GetMapping("/stock-items/typeahead")
    public List<InventoryMasterDtos.StockItemDto> typeaheadStockItems(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "q", required = false) String q,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.typeaheadStockItems(principal, companyId, q, limit);
    }

    @PostMapping("/stock-items")
    public ResponseEntity<InventoryMasterDtos.StockItemDto> createStockItem(
        @PathVariable("companyId") UUID companyId,
        @RequestBody InventoryMasterDtos.UpsertStockItemRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockItemDto created = inventoryMasterService.createStockItem(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/stock-items/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/stock-items/{stockItemId}")
    public ResponseEntity<InventoryMasterDtos.StockItemDto> getStockItem(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockItemId") UUID stockItemId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockItemDto item = inventoryMasterService.getStockItem(principal, companyId, stockItemId);
        return ResponseEntity.ok().eTag("\"v" + item.versionNo() + "\"").body(item);
    }

    @PutMapping("/stock-items/{stockItemId}")
    public ResponseEntity<InventoryMasterDtos.StockItemDto> updateStockItem(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockItemId") UUID stockItemId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestBody InventoryMasterDtos.UpsertStockItemRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.StockItemDto updated = inventoryMasterService.updateStockItem(
            actor, companyId, stockItemId, ifMatch, payload
        );
        return ResponseEntity.ok().eTag("\"v" + updated.versionNo() + "\"").body(updated);
    }

    @DeleteMapping("/stock-items/{stockItemId}")
    public ResponseEntity<Void> deleteStockItem(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockItemId") UUID stockItemId
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        inventoryMasterService.deleteStockItem(actor, companyId, stockItemId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stock-items/{stockItemId}/batches")
    public List<InventoryMasterDtos.StockBatchDto> listStockItemBatches(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("stockItemId") UUID stockItemId,
        @RequestParam(value = "godownId", required = false) UUID godownId,
        @RequestParam(value = "asOfDate", required = false) LocalDate asOfDate,
        @RequestParam(value = "includeZero", required = false, defaultValue = "false") boolean includeZero
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.listStockItemBatches(
            principal, companyId, stockItemId, godownId, asOfDate, includeZero
        );
    }

    // -------------------------------------------------------------------------
    // 5. Price Lists & Quantity Slabs (/price-lists)
    // -------------------------------------------------------------------------

    @GetMapping("/price-lists")
    public List<InventoryMasterDtos.PriceListDto> listPriceLists(@PathVariable("companyId") UUID companyId) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.listPriceLists(principal, companyId);
    }

    @GetMapping("/price-lists/lookup")
    public InventoryMasterDtos.PriceLookupResponse lookupPrice(
        @PathVariable("companyId") UUID companyId,
        @RequestParam("priceLevelName") String priceLevelName,
        @RequestParam("stockItemId") UUID stockItemId,
        @RequestParam(value = "qty", required = false) String qty,
        @RequestParam(value = "asOfDate", required = false) LocalDate asOfDate
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.lookupPrice(principal, companyId, priceLevelName, stockItemId, qty, asOfDate);
    }

    @PostMapping("/price-lists")
    public ResponseEntity<InventoryMasterDtos.PriceListDto> createPriceList(
        @PathVariable("companyId") UUID companyId,
        @RequestBody InventoryMasterDtos.UpsertPriceListRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.PriceListDto created = inventoryMasterService.createPriceList(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/price-lists/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/price-lists/{priceListId}")
    public ResponseEntity<InventoryMasterDtos.PriceListDto> getPriceList(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("priceListId") UUID priceListId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.PriceListDto pl = inventoryMasterService.getPriceList(principal, companyId, priceListId);
        return ResponseEntity.ok().eTag("\"v" + pl.versionNo() + "\"").body(pl);
    }

    @PutMapping("/price-lists/{priceListId}")
    public ResponseEntity<InventoryMasterDtos.PriceListDto> updatePriceList(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("priceListId") UUID priceListId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestBody InventoryMasterDtos.UpsertPriceListRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.PriceListDto updated = inventoryMasterService.updatePriceList(
            actor, companyId, priceListId, ifMatch, payload
        );
        return ResponseEntity.ok().eTag("\"v" + updated.versionNo() + "\"").body(updated);
    }

    // -------------------------------------------------------------------------
    // 6. Bills of Materials (/boms)
    // -------------------------------------------------------------------------

    @GetMapping("/boms")
    public List<InventoryMasterDtos.BomDto> listBoms(
        @PathVariable("companyId") UUID companyId,
        @RequestParam(value = "stockItemId", required = false) UUID stockItemId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        return inventoryMasterService.listBoms(principal, companyId, stockItemId);
    }

    @PostMapping("/boms")
    public ResponseEntity<InventoryMasterDtos.BomDto> createBom(
        @PathVariable("companyId") UUID companyId,
        @RequestBody InventoryMasterDtos.UpsertBomRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.BomDto created = inventoryMasterService.createBom(actor, companyId, payload);
        return ResponseEntity
            .created(URI.create("/api/v1/companies/" + companyId + "/boms/" + created.id()))
            .eTag("\"v" + created.versionNo() + "\"")
            .body(created);
    }

    @GetMapping("/boms/{bomId}")
    public ResponseEntity<InventoryMasterDtos.BomDto> getBom(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("bomId") UUID bomId
    ) {
        MoneyCraftPrincipal principal = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.BomDto bom = inventoryMasterService.getBom(principal, companyId, bomId);
        return ResponseEntity.ok().eTag("\"v" + bom.versionNo() + "\"").body(bom);
    }

    @PutMapping("/boms/{bomId}")
    public ResponseEntity<InventoryMasterDtos.BomDto> updateBom(
        @PathVariable("companyId") UUID companyId,
        @PathVariable("bomId") UUID bomId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestBody InventoryMasterDtos.UpsertBomRequest payload
    ) {
        MoneyCraftPrincipal actor = rbacGuard.requireAuthenticatedPrincipal();
        InventoryMasterDtos.BomDto updated = inventoryMasterService.updateBom(
            actor, companyId, bomId, ifMatch, payload
        );
        return ResponseEntity.ok().eTag("\"v" + updated.versionNo() + "\"").body(updated);
    }
}
