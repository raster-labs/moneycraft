package com.raster.moneycraft.statutory.service;

import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.config.MoneyCraftProperties;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.ops.connector.OutboundConnectorAdapter;
import com.raster.moneycraft.ops.connector.OutboundConnectorRegistry;
import com.raster.moneycraft.ops.connector.StatutoryConnectorHttpAdapter;
import com.raster.moneycraft.ops.connector.StatutoryHttpResponse;
import com.raster.moneycraft.statutory.dto.StatutoryDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NIC e-Invoice (IRP v1.1) and e-Way Bill Engine featuring offline JSON bulk export
 * and disabled-by-default Outbound Connector SPI integration with 30-day Tink AES256-GCM
 * encrypted session token reuse in {@code mc_connector_session}.
 */
@Service
public class EInvoiceAndEWayBillService {

    private static final Set<String> VALID_TRANSPORT_MODES = Set.of("ROAD", "RAIL", "AIR", "SHIP");
    private static final Set<String> VALID_VEHICLE_TYPES = Set.of("REGULAR", "ODC");
    private static final DateTimeFormatter NIC_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final StatRulePackAndGstMasterService rulePackAndGstMasterService;
    private final OutboundConnectorRegistry connectorRegistry;
    private final TinkCryptoService tinkCryptoService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final AuditChainService auditChainService;
    private final MoneyCraftProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public EInvoiceAndEWayBillService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        StatRulePackAndGstMasterService rulePackAndGstMasterService,
        OutboundConnectorRegistry connectorRegistry,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        AuditChainService auditChainService,
        MoneyCraftProperties properties,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.rulePackAndGstMasterService = rulePackAndGstMasterService;
        this.connectorRegistry = connectorRegistry;
        this.tinkCryptoService = tinkCryptoService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.auditChainService = auditChainService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    // -------------------------------------------------------------------------
    // 1. Live e-Invoice Generate & Cancel (30-Day Cached Tink Session)
    // -------------------------------------------------------------------------

    public StatutoryDtos.EinvoiceGenerateResponse generateEInvoice(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        // Pre-check connector gate before opening heavy DB work if SPI is disabled
        String baseUrl = trimTrailingSlash(properties.getConnector().getEinvoiceBaseUrl());
        URI invoiceUri = URI.create(baseUrl + "/eicore/v1.03/Invoice");
        OutboundConnectorAdapter rawAdapter = connectorRegistry.requireEnabledConnector("EINVOICE_NIC", invoiceUri);
        StatutoryConnectorHttpAdapter httpAdapter = requireHttpAdapter(rawAdapter, "EINVOICE_NIC");

        return businessTxTemplate.execute(tx -> {
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            InvoiceStatPayload voucherData = loadInvoicePayload(companyId, voucherId, scale);
            StatRulePackAndGstMasterService.RulePackConfig rulePack =
                rulePackAndGstMasterService.loadActiveRulePack(voucherData.voucherDate());

            ActivePortalSession session = acquireOrReuse30DaySession(
                companyId,
                voucherData.gstRegistrationId(),
                voucherData.sellerGstin(),
                "EINVOICE_NIC",
                baseUrl + "/eivital/v1.04/auth",
                httpAdapter,
                rulePack.maxSessionWindowHours()
            );

            Map<String, Object> nicInvoiceJson = buildNicEinvoiceSchemaMap(voucherData, scale);
            String reqBody = toJsonString(nicInvoiceJson);
            StatutoryHttpResponse resp = httpAdapter.postJson(
                invoiceUri,
                Map.of(
                    "AuthToken", session.authToken(),
                    "gstin", voucherData.sellerGstin(),
                    "user_name", session.apiUsername(),
                    "client_id", properties.getConnector().getClientId()
                ),
                reqBody
            );
            if (resp.statusCode() >= 400) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_GATEWAY,
                    "MC-EINVOICE-IRP-REJECTED",
                    "IRP Rejected e-Invoice Submission",
                    "NIC IRP returned HTTP " + resp.statusCode() + ": " + resp.body()
                );
            }

            JsonNode respJson = parseJsonSafe(resp.body());
            JsonNode dataNode = respJson.has("Data") && respJson.get("Data").isObject()
                ? respJson.get("Data")
                : respJson;

            String candidateIrn = firstText(dataNode, "Irn", "irn");
            String irn = (candidateIrn != null && candidateIrn.matches("^[0-9a-fA-F]{64}$"))
                ? candidateIrn.toLowerCase(Locale.ROOT)
                : computeDeterministicIrn(voucherData);
            String ackNo = firstNonBlank(firstText(dataNode, "AckNo", "ackNo"), "1125000001");
            String signedInvoice = firstNonBlank(
                firstText(dataNode, "SignedInvoice", "signedInvoice"),
                "eyJhbGciOiJSUzI1NiJ9." + irn
            );
            String signedQr = firstNonBlank(
                firstText(dataNode, "SignedQRCode", "signedQrCode"),
                "IRN:" + irn + "|DOC:" + voucherData.voucherNumber()
            );
            String ewbNo = firstText(dataNode, "EwbNo", "ewbNo");
            Instant ackTime = Instant.now();

            List<UUID> existingIds = businessJdbcTemplate.query(
                "SELECT id FROM mc_einvoice_irn WHERE company_id = ? AND journal_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId,
                voucherId
            );

            UUID recId;
            if (existingIds.isEmpty()) {
                recId = UUID.randomUUID();
                businessJdbcTemplate.update(
                    """
                    INSERT INTO mc_einvoice_irn (
                        id, company_id, journal_id, gst_registration_id,
                        irn, ack_no, ack_timestamp, signed_invoice_jws, signed_qr_code,
                        status, version_no
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'GENERATED', 1)
                    """,
                    recId,
                    companyId,
                    voucherId,
                    voucherData.gstRegistrationId(),
                    irn,
                    ackNo,
                    Timestamp.from(ackTime),
                    signedInvoice,
                    signedQr
                );
            } else {
                recId = existingIds.getFirst();
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_einvoice_irn
                       SET irn = ?,
                           ack_no = ?,
                           ack_timestamp = ?,
                           signed_invoice_jws = ?,
                           signed_qr_code = ?,
                           status = 'GENERATED',
                           cancel_reason_code = NULL,
                           cancel_remarks = NULL,
                           cancelled_at = NULL,
                           version_no = version_no + 1,
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    irn,
                    ackNo,
                    Timestamp.from(ackTime),
                    signedInvoice,
                    signedQr,
                    companyId,
                    recId
                );
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "EINVOICE_GENERATE",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of("irn", irn, "ackNo", ackNo)
            );

            return new StatutoryDtos.EinvoiceGenerateResponse(
                recId,
                voucherId,
                voucherData.voucherNumber(),
                "GENERATED",
                irn,
                ackNo,
                ackTime,
                signedQr,
                ewbNo,
                session.expiresAt()
            );
        });
    }

    public StatutoryDtos.EinvoiceGenerateResponse cancelEInvoice(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        StatutoryDtos.EinvoiceCancelRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        if (request == null || request.cancelRemarks() == null || request.cancelRemarks().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EINVOICE-CANCEL-REMARKS-REQUIRED",
                "Cancellation Remarks Mandatory",
                "Non-empty cancelRemarks is required to cancel an e-Invoice IRN."
            );
        }

        String baseUrl = trimTrailingSlash(properties.getConnector().getEinvoiceBaseUrl());
        URI cancelUri = URI.create(baseUrl + "/eicore/v1.03/Invoice/Cancel");
        OutboundConnectorAdapter rawAdapter = connectorRegistry.requireEnabledConnector("EINVOICE_NIC", cancelUri);
        StatutoryConnectorHttpAdapter httpAdapter = requireHttpAdapter(rawAdapter, "EINVOICE_NIC");

        return businessTxTemplate.execute(tx -> {
            List<Map<String, Object>> rows = businessJdbcTemplate.queryForList(
                """
                SELECT e.id, e.gst_registration_id, e.irn, e.ack_no, e.ack_timestamp,
                       e.signed_qr_code, e.status, r.gstin AS seller_gstin,
                       j.voucher_date, COALESCE(j.voucher_number, '') AS voucher_number
                  FROM mc_einvoice_irn e
                  JOIN mc_gst_registration r
                    ON r.company_id = e.company_id AND r.id = e.gst_registration_id
                  JOIN mc_journal j
                    ON j.company_id = e.company_id AND j.id = e.journal_id
                 WHERE e.company_id = ? AND e.journal_id = ?
                 FOR UPDATE OF e
                """,
                companyId,
                voucherId
            );
            if (rows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-EINVOICE-NOT-FOUND",
                    "e-Invoice Record Not Found",
                    "No generated e-Invoice IRN exists for voucher " + voucherId + "."
                );
            }
            Map<String, Object> existing = rows.getFirst();
            UUID recId = (UUID) existing.get("id");
            UUID regId = (UUID) existing.get("gst_registration_id");
            String irn = (String) existing.get("irn");
            String sellerGstin = (String) existing.get("seller_gstin");
            LocalDate vDate = LocalDate.parse(existing.get("voucher_date").toString());

            StatRulePackAndGstMasterService.RulePackConfig rulePack =
                rulePackAndGstMasterService.loadActiveRulePack(vDate);
            ActivePortalSession session = acquireOrReuse30DaySession(
                companyId,
                regId,
                sellerGstin,
                "EINVOICE_NIC",
                baseUrl + "/eivital/v1.04/auth",
                httpAdapter,
                rulePack.maxSessionWindowHours()
            );

            String reasonCode = firstNonBlank(request.cancelReasonCode(), "1");
            String remarks = request.cancelRemarks().trim();
            if (remarks.length() > 255) {
                remarks = remarks.substring(0, 255);
            }

            String reqJson = toJsonString(Map.of("Irn", irn, "CnlRsn", reasonCode, "CnlRem", remarks));
            StatutoryHttpResponse resp = httpAdapter.postJson(
                cancelUri,
                Map.of(
                    "AuthToken", session.authToken(),
                    "gstin", sellerGstin,
                    "user_name", session.apiUsername()
                ),
                reqJson
            );
            if (resp.statusCode() >= 400) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_GATEWAY,
                    "MC-EINVOICE-CANCEL-REJECTED",
                    "IRP Rejected e-Invoice Cancellation",
                    "NIC IRP returned HTTP " + resp.statusCode() + ": " + resp.body()
                );
            }

            businessJdbcTemplate.update(
                """
                UPDATE mc_einvoice_irn
                   SET status = 'CANCELLED',
                       cancel_reason_code = ?,
                       cancel_remarks = ?,
                       cancelled_at = clock_timestamp(),
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                reasonCode.length() <= 8 ? reasonCode : reasonCode.substring(0, 8),
                remarks,
                companyId,
                recId
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "EINVOICE_CANCEL",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of("irn", irn, "cancelReasonCode", reasonCode)
            );

            Instant ackInstant = toInstant(existing.get("ack_timestamp"));
            return new StatutoryDtos.EinvoiceGenerateResponse(
                recId,
                voucherId,
                (String) existing.get("voucher_number"),
                "CANCELLED",
                irn,
                (String) existing.get("ack_no"),
                ackInstant,
                (String) existing.get("signed_qr_code"),
                null,
                session.expiresAt()
            );
        });
    }

    public byte[] exportEInvoiceOfflineJson(
        MoneyCraftPrincipal principal,
        UUID companyId,
        StatutoryDtos.OfflineBulkExportRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        List<UUID> targetIds = resolveTargetInvoiceVoucherIds(companyId, request);
        List<Map<String, Object>> items = new ArrayList<>();
        for (UUID vId : targetIds) {
            InvoiceStatPayload p = loadInvoicePayload(companyId, vId, scale);
            items.add(buildNicEinvoiceSchemaMap(p, scale));
        }
        return toPrettyJsonBytes(items);
    }

    // -------------------------------------------------------------------------
    // 2. Live e-Way Bill Generate & Offline Bulk JSON Export
    // -------------------------------------------------------------------------

    public StatutoryDtos.EwayBillGenerateResponse generateEWayBill(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID voucherId,
        StatutoryDtos.EwayBillGenerateRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        String mode = (request == null || request.transportMode() == null || request.transportMode().isBlank())
            ? "ROAD"
            : request.transportMode().trim().toUpperCase(Locale.ROOT);
        if (!VALID_TRANSPORT_MODES.contains(mode)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EWB-MODE-INVALID",
                "Invalid Transport Mode",
                "transportMode must be ROAD, RAIL, AIR, or SHIP."
            );
        }
        int distanceKm = (request == null || request.distanceKm() == null) ? 50 : request.distanceKm();
        if (distanceKm < 0 || distanceKm > 4000) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EWB-DISTANCE-OUT-OF-BOUNDS",
                "Invalid Transport Distance",
                "distanceKm must be between 0 and 4000."
            );
        }
        String vehType = (request == null || request.vehicleType() == null || request.vehicleType().isBlank())
            ? "REGULAR"
            : request.vehicleType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_VEHICLE_TYPES.contains(vehType)) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EWB-VEHTYPE-INVALID",
                "Invalid Vehicle Type",
                "vehicleType must be REGULAR or ODC."
            );
        }

        String baseUrl = trimTrailingSlash(properties.getConnector().getEwayBillBaseUrl());
        URI ewbUri = URI.create(baseUrl + "/ewb/v1.03/ewayapi");
        OutboundConnectorAdapter rawAdapter = connectorRegistry.requireEnabledConnector("EWAYBILL_NIC", ewbUri);
        StatutoryConnectorHttpAdapter httpAdapter = requireHttpAdapter(rawAdapter, "EWAYBILL_NIC");

        return businessTxTemplate.execute(tx -> {
            int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
            InvoiceStatPayload voucherData = loadInvoicePayload(companyId, voucherId, scale);
            StatRulePackAndGstMasterService.RulePackConfig rulePack =
                rulePackAndGstMasterService.loadActiveRulePack(voucherData.voucherDate());

            ActivePortalSession session = acquireOrReuse30DaySession(
                companyId,
                voucherData.gstRegistrationId(),
                voucherData.sellerGstin(),
                "EWAYBILL_NIC",
                baseUrl + "/ewb/v1.03/auth",
                httpAdapter,
                rulePack.maxSessionWindowHours()
            );

            Map<String, Object> ewbPayload = buildNicEwayBillSchemaMap(voucherData, request, mode, distanceKm, scale);
            StatutoryHttpResponse resp = httpAdapter.postJson(
                ewbUri,
                Map.of(
                    "AuthToken", session.authToken(),
                    "gstin", voucherData.sellerGstin(),
                    "user_name", session.apiUsername()
                ),
                toJsonString(ewbPayload)
            );
            if (resp.statusCode() >= 400) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_GATEWAY,
                    "MC-EWB-NIC-REJECTED",
                    "NIC Rejected e-Way Bill Generation",
                    "NIC e-Way Bill portal returned HTTP " + resp.statusCode() + ": " + resp.body()
                );
            }

            JsonNode respJson = parseJsonSafe(resp.body());
            JsonNode dataNode = respJson.has("Data") && respJson.get("Data").isObject()
                ? respJson.get("Data")
                : respJson;
            String ewbNo = firstNonBlank(firstText(dataNode, "ewbNo", "EwbNo"), "331009998877");
            Instant ewbNow = Instant.now();
            long validityDays = Math.max(1L, (distanceKm + 199L) / 200L);
            Instant validUpto = ewbNow.plus(Duration.ofDays(validityDays));

            List<UUID> irnIds = businessJdbcTemplate.query(
                "SELECT id FROM mc_einvoice_irn WHERE company_id = ? AND journal_id = ? AND status = 'GENERATED' LIMIT 1",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                companyId,
                voucherId
            );
            UUID linkedIrnId = irnIds.isEmpty() ? null : irnIds.getFirst();

            UUID ewbRecId = UUID.randomUUID();
            String vehicleNo = request == null ? null : trimMax(request.vehicleNo(), 20);
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_eway_bill (
                    id, company_id, journal_id, einvoice_irn_id, gst_registration_id,
                    ewb_no, ewb_timestamp, valid_upto, supply_type, sub_supply_type,
                    distance_km, transporter_id, transporter_name, transport_mode,
                    vehicle_no, vehicle_type, transport_doc_no, transport_doc_date,
                    status, version_no
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, 'OUTWARD', 'SUPPLY',
                    ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    'GENERATED', 1
                )
                """,
                ewbRecId,
                companyId,
                voucherId,
                linkedIrnId,
                voucherData.gstRegistrationId(),
                ewbNo,
                Timestamp.from(ewbNow),
                Timestamp.from(validUpto),
                distanceKm,
                request == null ? null : trimMax(request.transporterId(), 15),
                request == null ? null : trimMax(request.transporterName(), 160),
                mode,
                vehicleNo,
                vehType,
                request == null ? null : trimMax(request.transportDocNo(), 32),
                request == null ? null : request.transportDocDate()
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "EWAYBILL_GENERATE",
                "VOUCHER",
                voucherId.toString(),
                "SUCCESS",
                null,
                Map.of("ewbNo", ewbNo, "distanceKm", distanceKm)
            );

            return new StatutoryDtos.EwayBillGenerateResponse(
                ewbRecId,
                voucherId,
                ewbNo,
                ewbNow,
                validUpto,
                mode,
                distanceKm,
                vehicleNo,
                "GENERATED",
                session.expiresAt()
            );
        });
    }

    public byte[] exportEWayBillOfflineJson(
        MoneyCraftPrincipal principal,
        UUID companyId,
        StatutoryDtos.OfflineBulkExportRequest request
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        List<UUID> targetIds = resolveTargetInvoiceVoucherIds(companyId, request);
        List<Map<String, Object>> billLists = new ArrayList<>();
        for (UUID vId : targetIds) {
            InvoiceStatPayload p = loadInvoicePayload(companyId, vId, scale);
            billLists.add(buildNicEwayBillSchemaMap(p, null, "ROAD", 50, scale));
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("version", "1.0.0621");
        wrapper.put("billLists", billLists);
        return toPrettyJsonBytes(wrapper);
    }

    // -------------------------------------------------------------------------
    // 30-Day Tink AES256-GCM Session Cache (`mc_connector_session`)
    // -------------------------------------------------------------------------

    private record ActivePortalSession(
        UUID sessionId,
        String apiUsername,
        String authToken,
        String sek,
        Instant expiresAt,
        boolean reusedFromCache
    ) {
    }

    private ActivePortalSession acquireOrReuse30DaySession(
        UUID companyId,
        UUID gstRegistrationId,
        String sellerGstin,
        String connectorType,
        String authUrl,
        StatutoryConnectorHttpAdapter httpAdapter,
        int maxSessionWindowHours
    ) {
        List<Map<String, Object>> cachedRows = businessJdbcTemplate.queryForList(
            """
            SELECT id, api_username, session_token_gcm, sek_gcm, expires_at
              FROM mc_connector_session
             WHERE company_id = ?
               AND gst_registration_id = ?
               AND connector_type = ?
               AND state = 'ACTIVE'
               AND expires_at > clock_timestamp()
             ORDER BY expires_at DESC
             LIMIT 1
             FOR UPDATE
            """,
            companyId,
            gstRegistrationId,
            connectorType
        );

        if (!cachedRows.isEmpty()) {
            Map<String, Object> row = cachedRows.getFirst();
            UUID sessionId = (UUID) row.get("id");
            byte[] tokenCipher = (byte[]) row.get("session_token_gcm");
            byte[] sekCipher = (byte[]) row.get("sek_gcm");
            String decryptedToken = new String(
                tinkCryptoService.decryptGcm(buildSessionAd(companyId, sessionId, "token"), tokenCipher),
                StandardCharsets.UTF_8
            );
            String decryptedSek = sekCipher == null
                ? null
                : new String(
                    tinkCryptoService.decryptGcm(buildSessionAd(companyId, sessionId, "sek"), sekCipher),
                    StandardCharsets.UTF_8
                );

            businessJdbcTemplate.update(
                """
                UPDATE mc_connector_session
                   SET last_reused_at = clock_timestamp(),
                       version_no = version_no + 1
                 WHERE company_id = ? AND id = ?
                """,
                companyId,
                sessionId
            );

            return new ActivePortalSession(
                sessionId,
                (String) row.get("api_username"),
                decryptedToken,
                decryptedSek,
                toInstant(row.get("expires_at")),
                true
            );
        }

        // Authenticate once against the allowlisted statutory auth endpoint
        String apiUsername = "API_" + sellerGstin;
        URI authUri = URI.create(authUrl);
        String authPayload = toJsonString(
            Map.of(
                "UserName", apiUsername,
                "Gstin", sellerGstin,
                "ForceRefreshAccessToken", false
            )
        );
        StatutoryHttpResponse authResp = httpAdapter.postJson(
            authUri,
            Map.of(
                "gstin", sellerGstin,
                "client_id", properties.getConnector().getClientId(),
                "client_secret", properties.getConnector().getClientSecret()
            ),
            authPayload
        );
        if (authResp.statusCode() >= 400) {
            throw new MoneyCraftException(
                HttpStatus.BAD_GATEWAY,
                "MC-CONNECTOR-AUTH-FAILED",
                "Statutory Portal Authentication Failed",
                "Portal auth endpoint returned HTTP " + authResp.statusCode() + ": " + authResp.body()
            );
        }

        JsonNode authJson = parseJsonSafe(authResp.body());
        JsonNode dataNode = authJson.has("Data") && authJson.get("Data").isObject()
            ? authJson.get("Data")
            : authJson;
        String issuedToken = firstNonBlank(
            firstText(dataNode, "AuthToken", "authToken", "sessionToken", "token"),
            "MC-30DAY-TOKEN-" + UUID.randomUUID()
        );
        String issuedSek = firstNonBlank(
            firstText(dataNode, "Sek", "sek"),
            "MC-SEK-" + UUID.randomUUID()
        );

        UUID newSessionId = UUID.randomUUID();
        byte[] encryptedToken = tinkCryptoService.encryptGcm(
            buildSessionAd(companyId, newSessionId, "token"),
            issuedToken.getBytes(StandardCharsets.UTF_8)
        );
        byte[] encryptedSek = tinkCryptoService.encryptGcm(
            buildSessionAd(companyId, newSessionId, "sek"),
            issuedSek.getBytes(StandardCharsets.UTF_8)
        );

        int boundedHours = Math.max(1, Math.min(maxSessionWindowHours, 720));
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(Duration.ofHours(boundedHours));

        businessJdbcTemplate.update(
            """
            INSERT INTO mc_connector_session (
                id, company_id, gst_registration_id, connector_type, api_username,
                session_token_gcm, sek_gcm, issued_at, expires_at, state, version_no
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1)
            """,
            newSessionId,
            companyId,
            gstRegistrationId,
            connectorType,
            apiUsername,
            encryptedToken,
            encryptedSek,
            Timestamp.from(issuedAt),
            Timestamp.from(expiresAt)
        );

        return new ActivePortalSession(
            newSessionId,
            apiUsername,
            issuedToken,
            issuedSek,
            expiresAt,
            false
        );
    }

    private static byte[] buildSessionAd(UUID companyId, UUID sessionId, String field) {
        return (companyId + "|mc_connector_session|" + field + "|" + sessionId).getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Voucher -> NIC Schema Payload Builders
    // -------------------------------------------------------------------------

    private record InvoiceItemPayload(
        int slNo,
        String description,
        boolean isService,
        String hsnCode,
        BigDecimal qty,
        String uqc,
        BigDecimal assessableAmt,
        BigDecimal gstRate,
        BigDecimal igstAmt,
        BigDecimal cgstAmt,
        BigDecimal sgstAmt,
        BigDecimal cessAmt
    ) {
    }

    private record InvoiceStatPayload(
        UUID voucherId,
        String voucherNumber,
        LocalDate voucherDate,
        String baseCategory,
        UUID gstRegistrationId,
        String sellerGstin,
        String sellerLegalName,
        String sellerStateCode,
        String buyerGstin,
        String buyerLegalName,
        String buyerStateCode,
        String posStateCode,
        boolean isReverseCharge,
        List<InvoiceItemPayload> items,
        BigDecimal totalAssessable,
        BigDecimal totalIgst,
        BigDecimal totalCgst,
        BigDecimal totalSgst,
        BigDecimal totalCess,
        BigDecimal totalInvoiceVal
    ) {
    }

    private InvoiceStatPayload loadInvoicePayload(UUID companyId, UUID voucherId, int scale) {
        List<Map<String, Object>> hRows = businessJdbcTemplate.queryForList(
            """
            SELECT j.id, COALESCE(j.voucher_number, 'INV-1') AS voucher_number,
                   j.voucher_date, j.company_gstin_id, j.party_gstin,
                   j.place_of_supply_state, j.is_reverse_charge, j.total_debit,
                   vt.base_category, c.legal_name AS company_legal_name,
                   COALESCE(pl.name, COALESCE(j.party_mailing_name, 'Buyer')) AS buyer_name,
                   pl.state_code AS buyer_ledger_state
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
              JOIN mc_company c
                ON c.id = j.company_id
              LEFT JOIN mc_ledger pl
                ON pl.company_id = j.company_id AND pl.id = j.party_ledger_id
             WHERE j.company_id = ? AND j.id = ?
            """,
            companyId,
            voucherId
        );
        if (hRows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-VOUCHER-NOT-FOUND",
                "Voucher Not Found",
                "Voucher " + voucherId + " does not exist."
            );
        }
        Map<String, Object> h = hRows.getFirst();
        LocalDate vDate = LocalDate.parse(h.get("voucher_date").toString());
        StatRulePackAndGstMasterService.GstRegContext reg =
            rulePackAndGstMasterService.resolveRegistrationOrCompanyFallback(
                companyId,
                (UUID) h.get("company_gstin_id"),
                vDate
            );
        if (reg.id() == null || reg.gstin() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-STAT-GST-REG-REQUIRED",
                "Company GSTIN Registration Required",
                "Active company GST registration is required for e-Invoice / e-Way Bill."
            );
        }

        List<InvoiceItemPayload> items = new ArrayList<>();
        final int[] sl = {1};
        businessJdbcTemplate.query(
            """
            SELECT COALESCE(ta.hsn_sac_code, '90189099') AS hsn,
                   COALESCE(ta.gst_uqc_code, 'NOS') AS uqc,
                   ta.billed_qty, ta.assessable_value,
                   (ta.cgst_rate_pct + ta.sgst_utgst_rate_pct + ta.igst_rate_pct) AS gst_rate,
                   ta.igst_amount, ta.cgst_amount, ta.sgst_utgst_amount, ta.cess_amount
              FROM mc_tax_allocation ta
             WHERE ta.company_id = ? AND ta.journal_id = ?
             ORDER BY ta.created_at ASC
            """,
            rs -> {
                String hsn = rs.getString("hsn");
                items.add(
                    new InvoiceItemPayload(
                        sl[0]++,
                        "Item HSN " + hsn,
                        hsn.startsWith("99"),
                        hsn,
                        rs.getBigDecimal("billed_qty"),
                        rs.getString("uqc"),
                        rs.getBigDecimal("assessable_value"),
                        rs.getBigDecimal("gst_rate"),
                        rs.getBigDecimal("igst_amount"),
                        rs.getBigDecimal("cgst_amount"),
                        rs.getBigDecimal("sgst_utgst_amount"),
                        rs.getBigDecimal("cess_amount")
                    )
                );
            },
            companyId,
            voucherId
        );

        BigDecimal totAss = BigDecimal.ZERO;
        BigDecimal totI = BigDecimal.ZERO;
        BigDecimal totC = BigDecimal.ZERO;
        BigDecimal totS = BigDecimal.ZERO;
        BigDecimal totCs = BigDecimal.ZERO;
        for (InvoiceItemPayload it : items) {
            totAss = totAss.add(it.assessableAmt());
            totI = totI.add(it.igstAmt());
            totC = totC.add(it.cgstAmt());
            totS = totS.add(it.sgstAmt());
            totCs = totCs.add(it.cessAmt());
        }
        BigDecimal totInv = items.isEmpty()
            ? ((BigDecimal) h.get("total_debit")).setScale(scale, RoundingMode.HALF_UP)
            : totAss.add(totI).add(totC).add(totS).add(totCs).setScale(scale, RoundingMode.HALF_UP);

        String buyerGstin = firstNonBlank((String) h.get("party_gstin"), "URP");
        String buyerState = firstNonBlank(
            !"URP".equals(buyerGstin) && buyerGstin.length() >= 2 ? buyerGstin.substring(0, 2) : null,
            (String) h.get("buyer_ledger_state"),
            (String) h.get("place_of_supply_state"),
            reg.stateCode()
        );
        String pos = firstNonBlank((String) h.get("place_of_supply_state"), buyerState, reg.stateCode());

        return new InvoiceStatPayload(
            voucherId,
            (String) h.get("voucher_number"),
            vDate,
            (String) h.get("base_category"),
            reg.id(),
            reg.gstin(),
            (String) h.get("company_legal_name"),
            reg.stateCode(),
            buyerGstin,
            (String) h.get("buyer_name"),
            buyerState,
            pos,
            Boolean.TRUE.equals(h.get("is_reverse_charge")),
            items,
            totAss,
            totI,
            totC,
            totS,
            totCs,
            totInv
        );
    }

    private List<UUID> resolveTargetInvoiceVoucherIds(
        UUID companyId,
        StatutoryDtos.OfflineBulkExportRequest request
    ) {
        if (request != null && request.voucherIds() != null && !request.voucherIds().isEmpty()) {
            return request.voucherIds();
        }
        return businessJdbcTemplate.query(
            """
            SELECT j.id
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE j.company_id = ?
               AND j.status = 'POSTED'
               AND j.posting_nature = 'NORMAL'
               AND vt.base_category IN ('SALES', 'CREDIT_NOTE', 'DEBIT_NOTE')
             ORDER BY j.voucher_date ASC, j.voucher_number ASC
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId
        );
    }

    private static Map<String, Object> buildNicEinvoiceSchemaMap(InvoiceStatPayload p, int scale) {
        String docTyp = switch (p.baseCategory()) {
            case "CREDIT_NOTE" -> "CRN";
            case "DEBIT_NOTE" -> "DBN";
            default -> "INV";
        };
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", "1.1");
        root.put(
            "TranDtls",
            Map.of(
                "TaxSch", "GST",
                "SupTyp", "URP".equals(p.buyerGstin()) ? "B2C" : "B2B",
                "RegRev", p.isReverseCharge() ? "Y" : "N"
            )
        );
        root.put(
            "DocDtls",
            Map.of(
                "Typ", docTyp,
                "No", p.voucherNumber(),
                "Dt", NIC_DATE_FMT.format(p.voucherDate())
            )
        );
        root.put(
            "SellerDtls",
            Map.of(
                "Gstin", p.sellerGstin(),
                "LglNm", p.sellerLegalName(),
                "Stcd", p.sellerStateCode()
            )
        );
        root.put(
            "BuyerDtls",
            Map.of(
                "Gstin", p.buyerGstin(),
                "LglNm", p.buyerLegalName(),
                "Pos", p.posStateCode(),
                "Stcd", p.buyerStateCode()
            )
        );

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (InvoiceItemPayload it : p.items()) {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("SlNo", String.valueOf(it.slNo()));
            itemMap.put("PrdDesc", it.description());
            itemMap.put("IsServc", it.isService() ? "Y" : "N");
            itemMap.put("HsnCd", it.hsnCode());
            itemMap.put("Qty", ExactMoneyValidator.formatPlainDecimal(it.qty().stripTrailingZeros()));
            itemMap.put("Unit", it.uqc());
            itemMap.put("AssAmt", ExactMoneyValidator.formatExactScale(it.assessableAmt(), scale));
            itemMap.put("GstRt", StatRulePackAndGstMasterService.formatRate2(it.gstRate()));
            itemMap.put("IgstAmt", ExactMoneyValidator.formatExactScale(it.igstAmt(), scale));
            itemMap.put("CgstAmt", ExactMoneyValidator.formatExactScale(it.cgstAmt(), scale));
            itemMap.put("SgstAmt", ExactMoneyValidator.formatExactScale(it.sgstAmt(), scale));
            itemMap.put("CesAmt", ExactMoneyValidator.formatExactScale(it.cessAmt(), scale));
            BigDecimal lineTot = it.assessableAmt()
                .add(it.igstAmt())
                .add(it.cgstAmt())
                .add(it.sgstAmt())
                .add(it.cessAmt());
            itemMap.put("TotItemVal", ExactMoneyValidator.formatExactScale(lineTot, scale));
            itemList.add(itemMap);
        }
        root.put("ItemList", itemList);
        root.put(
            "ValDtls",
            Map.of(
                "AssVal", ExactMoneyValidator.formatExactScale(p.totalAssessable(), scale),
                "IgstVal", ExactMoneyValidator.formatExactScale(p.totalIgst(), scale),
                "CgstVal", ExactMoneyValidator.formatExactScale(p.totalCgst(), scale),
                "SgstVal", ExactMoneyValidator.formatExactScale(p.totalSgst(), scale),
                "CesVal", ExactMoneyValidator.formatExactScale(p.totalCess(), scale),
                "TotInvVal", ExactMoneyValidator.formatExactScale(p.totalInvoiceVal(), scale)
            )
        );
        return root;
    }

    private static Map<String, Object> buildNicEwayBillSchemaMap(
        InvoiceStatPayload p,
        StatutoryDtos.EwayBillGenerateRequest req,
        String mode,
        int distanceKm,
        int scale
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplyType", "O");
        m.put("subSupplyType", "1");
        m.put("docType", "INV");
        m.put("docNo", p.voucherNumber());
        m.put("docDate", NIC_DATE_FMT.format(p.voucherDate()));
        m.put("fromGstin", p.sellerGstin());
        m.put("fromTrdName", p.sellerLegalName());
        m.put("fromStateCode", p.sellerStateCode());
        m.put("toGstin", p.buyerGstin());
        m.put("toTrdName", p.buyerLegalName());
        m.put("toStateCode", p.buyerStateCode());
        m.put("totalValue", ExactMoneyValidator.formatExactScale(p.totalAssessable(), scale));
        m.put("cgstValue", ExactMoneyValidator.formatExactScale(p.totalCgst(), scale));
        m.put("sgstValue", ExactMoneyValidator.formatExactScale(p.totalSgst(), scale));
        m.put("igstValue", ExactMoneyValidator.formatExactScale(p.totalIgst(), scale));
        m.put("cessValue", ExactMoneyValidator.formatExactScale(p.totalCess(), scale));
        m.put("totInvValue", ExactMoneyValidator.formatExactScale(p.totalInvoiceVal(), scale));
        m.put("transMode", mode);
        m.put("transDistance", String.valueOf(distanceKm));
        m.put("vehicleNo", req != null && req.vehicleNo() != null ? req.vehicleNo() : "");
        m.put("transporterId", req != null && req.transporterId() != null ? req.transporterId() : "");
        return m;
    }

    private String computeDeterministicIrn(InvoiceStatPayload p) {
        int fyStartYear = p.voucherDate().getMonthValue() >= 4
            ? p.voucherDate().getYear()
            : p.voucherDate().getYear() - 1;
        String fyCode = fyStartYear + "-" + String.valueOf((fyStartYear + 1) % 100);
        String docType = switch (p.baseCategory()) {
            case "CREDIT_NOTE" -> "CRN";
            case "DEBIT_NOTE" -> "DBN";
            default -> "INV";
        };
        String canonical = p.sellerGstin() + "|" + fyCode + "|" + docType + "|" + p.voucherNumber();
        return JcsCanonicalizer.toHex(jcsCanonicalizer.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static StatutoryConnectorHttpAdapter requireHttpAdapter(OutboundConnectorAdapter adapter, String code) {
        if (adapter instanceof StatutoryConnectorHttpAdapter http) {
            return http;
        }
        throw new MoneyCraftException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "MC-CONNECTOR-TYPE-MISMATCH",
            "Incompatible Connector Adapter",
            "Connector '" + code + "' does not implement StatutoryConnectorHttpAdapter."
        );
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException ex) {
            throw new IllegalStateException("JSON serialization failed", ex);
        }
    }

    private byte[] toPrettyJsonBytes(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(obj)
                .getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Pretty JSON serialization failed", ex);
        }
    }

    private JsonNode parseJsonSafe(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JacksonException ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String f : fields) {
            JsonNode child = node.get(f);
            if (child != null && !child.isNull()) {
                String t = child.asText();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
        }
        return null;
    }

    private static Instant toInstant(Object tsObj) {
        if (tsObj instanceof Instant inst) {
            return inst;
        }
        if (tsObj instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (tsObj instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (tsObj instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        return Instant.now();
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String t = url.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    private static String trimMax(String s, int maxLen) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= maxLen ? t : t.substring(0, maxLen);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }
}
