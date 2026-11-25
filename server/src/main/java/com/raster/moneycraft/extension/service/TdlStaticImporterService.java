package com.raster.moneycraft.extension.service;

import com.raster.moneycraft.extension.dto.ExtensionDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.JcsCanonicalizer;
import com.raster.moneycraft.foundation.crypto.TinkCryptoService;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Static TDL source intake, parser, inactive draft extension synthesizer, and 5-disposition
 * Functional Parity Matrix generator (TDL-001..004). Never executes source or native artifacts.
 */
@Service
public class TdlStaticImporterService {

    public static final String DISP_TRANSLATED = "TRANSLATED_VERIFICATION_PENDING";
    public static final String DISP_REQUIRES_MAPPING = "REQUIRES_MAPPING";
    public static final String DISP_UNSUPPORTED = "UNSUPPORTED";
    public static final String DISP_BLOCKED = "BLOCKED_DEPENDENCY";
    public static final String DISP_NOT_APPLICABLE = "NOT_APPLICABLE_WITH_EXPLANATION";

    private static final Pattern BRACKET_DEF_PATTERN = Pattern.compile(
        "^\\[\\s*([#!*+]?)\\s*([A-Za-z][A-Za-z0-9 _-]*)\\s*:\\s*([^\\]]+)\\]"
    );
    private static final Pattern INCLUDE_DIRECTIVE_PATTERN = Pattern.compile(
        "(?i)^\\s*\\[?\\s*Include\\s*:\\s*([^\\]\\r\\n]+)\\]?\\s*$"
    );
    private static final Pattern UDF_DECL_PATTERN = Pattern.compile(
        "^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z]+)\\s*:\\s*([0-9]+)\\s*$"
    );
    private static final Pattern FORMULA_DECL_PATTERN = Pattern.compile(
        "^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*:\\s*(.+)$"
    );

    private final AuthorityGuard authorityGuard;
    private final ExtensionAccessHelper accessHelper;
    private final ExtensionWorkbenchService workbenchService;
    private final TinkCryptoService tinkCryptoService;
    private final JcsCanonicalizer jcsCanonicalizer;
    private final AuditChainService auditChainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public TdlStaticImporterService(
        AuthorityGuard authorityGuard,
        ExtensionAccessHelper accessHelper,
        ExtensionWorkbenchService workbenchService,
        TinkCryptoService tinkCryptoService,
        JcsCanonicalizer jcsCanonicalizer,
        AuditChainService auditChainService,
        ObjectMapper objectMapper,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.accessHelper = accessHelper;
        this.workbenchService = workbenchService;
        this.tinkCryptoService = tinkCryptoService;
        this.jcsCanonicalizer = jcsCanonicalizer;
        this.auditChainService = auditChainService;
        this.objectMapper = objectMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public List<ExtensionDtos.TdlImportProjectDto> listProjects(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        accessHelper.requireCompanyRead(principal, companyId);
        return businessJdbcTemplate.query(
            """
            SELECT id, company_id, project_name, file_name, source_version_claim,
                   source_sha256, has_compiled_tcp_block, has_native_dll_block,
                   has_unresolved_includes, constructs_json, parity_matrix_json,
                   summary_counts_json, draft_extension_id, status, created_at
              FROM mc_tdl_import_project
             WHERE company_id = ?
             ORDER BY created_at DESC
            """,
            (rs, rowNum) -> mapProjectRow(rs),
            companyId
        );
    }

    public ExtensionDtos.TdlImportProjectDto getProject(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID projectId
    ) {
        accessHelper.requireCompanyRead(principal, companyId);
        List<ExtensionDtos.TdlImportProjectDto> rows = businessJdbcTemplate.query(
            """
            SELECT id, company_id, project_name, file_name, source_version_claim,
                   source_sha256, has_compiled_tcp_block, has_native_dll_block,
                   has_unresolved_includes, constructs_json, parity_matrix_json,
                   summary_counts_json, draft_extension_id, status, created_at
              FROM mc_tdl_import_project
             WHERE company_id = ? AND id = ?
            """,
            (rs, rowNum) -> mapProjectRow(rs),
            companyId,
            projectId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-TDL-PROJECT-NOT-FOUND",
                "TDL Import Project Not Found",
                "TDL import project " + projectId + " does not exist in company " + companyId + "."
            );
        }
        return rows.getFirst();
    }

    public ExtensionDtos.TdlImportProjectDto importTdlProject(
        MoneyCraftPrincipal actor,
        UUID companyId,
        ExtensionDtos.ImportTdlProjectRequest request
    ) {
        authorityGuard.assertWritable();
        accessHelper.requireExtensionAuthor(actor, companyId);
        if (request == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-TDL-PAYLOAD-REQUIRED",
                "TDL Source Payload Required",
                "Provide tdlText or base64Content for static TDL intake."
            );
        }

        byte[] rawBytes = resolveSourceBytes(request);
        String fileName = request.fileName() == null || request.fileName().isBlank()
            ? "project.tdl"
            : request.fileName().trim();
        String projectName = request.projectName() == null || request.projectName().isBlank()
            ? deriveProjectName(fileName)
            : request.projectName().trim();
        String versionClaim = request.sourceVersionClaim() == null || request.sourceVersionClaim().isBlank()
            ? "UNSPECIFIED"
            : request.sourceVersionClaim().trim();

        boolean isCompiledTcp = detectCompiledTcp(fileName, rawBytes);
        Map<String, String> suppliedIncludes = normalizeIncludesMap(request.includedFiles());

        ParsedTdlAnalysis analysis = isCompiledTcp
            ? buildCompiledTcpBlockedAnalysis(fileName)
            : analyzePlainTextTdl(projectName, fileName, rawBytes, suppliedIncludes);

        return businessTxTemplate.execute(tx -> {
            accessHelper.ensurePrincipalProjected(actor);
            UUID projectId = UUID.randomUUID();
            byte[] sha256 = jcsCanonicalizer.sha256(rawBytes);
            byte[] aad = ("mc_tdl_import_project|" + companyId + "|" + projectId).getBytes(StandardCharsets.UTF_8);
            byte[] cipherGcm = tinkCryptoService.encryptGcm(aad, rawBytes);

            UUID draftExtensionId = null;
            if (analysis.draftDefinition() != null) {
                draftExtensionId = workbenchService.persistDraftPackageInCurrentTx(
                    actor,
                    companyId,
                    analysis.draftDefinition()
                );
            }

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_tdl_import_project (
                    id, company_id, project_name, file_name, source_version_claim,
                    source_sha256, source_cipher_gcm, has_compiled_tcp_block,
                    has_native_dll_block, has_unresolved_includes, constructs_json,
                    parity_matrix_json, summary_counts_json, draft_extension_id,
                    status, created_by
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?::jsonb,
                    ?::jsonb, ?::jsonb, ?,
                    ?, ?
                )
                """,
                projectId,
                companyId,
                projectName,
                fileName,
                versionClaim,
                sha256,
                cipherGcm,
                analysis.hasCompiledTcpBlock(),
                analysis.hasNativeDllBlock(),
                analysis.hasUnresolvedIncludes(),
                toJson(analysis.constructs()),
                toJson(analysis.parityMatrix()),
                toJson(analysis.summaryCounts()),
                draftExtensionId,
                analysis.projectStatus(),
                actor.principalId()
            );

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "TDL_STATIC_IMPORT",
                "TDL_IMPORT_PROJECT",
                projectId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "projectName", projectName,
                    "fileName", fileName,
                    "status", analysis.projectStatus(),
                    "constructCount", analysis.constructs().size(),
                    "draftExtensionCreated", draftExtensionId != null
                )
            );

            return getProject(actor, companyId, projectId);
        });
    }

    private static byte[] resolveSourceBytes(ExtensionDtos.ImportTdlProjectRequest req) {
        if (req.base64Content() != null && !req.base64Content().isBlank()) {
            try {
                return Base64.getMimeDecoder().decode(req.base64Content().trim());
            } catch (IllegalArgumentException ex) {
                throw new MoneyCraftException(
                    HttpStatus.BAD_REQUEST,
                    "MC-TDL-BASE64-INVALID",
                    "Invalid Base64 TDL Source",
                    "Failed to decode base64 TDL payload."
                );
            }
        }
        if (req.tdlText() != null && !req.tdlText().isBlank()) {
            return req.tdlText().getBytes(StandardCharsets.UTF_8);
        }
        throw new MoneyCraftException(
            HttpStatus.BAD_REQUEST,
            "MC-TDL-SOURCE-EMPTY",
            "Empty TDL Source",
            "TDL source bytes must not be empty."
        );
    }

    private static boolean detectCompiledTcp(String fileName, byte[] rawBytes) {
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".tcp")) {
            return true;
        }
        if (rawBytes.length >= 4 && rawBytes[0] == 'T' && rawBytes[1] == 'C' && rawBytes[2] == 'P') {
            return true;
        }
        for (int i = 0; i < Math.min(rawBytes.length, 512); i++) {
            byte b = rawBytes[i];
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static ParsedTdlAnalysis buildCompiledTcpBlockedAnalysis(String fileName) {
        ExtensionDtos.TdlConstructDto construct = new ExtensionDtos.TdlConstructDto(
            1,
            "COMPILED_TCP_ARTIFACT",
            fileName,
            fileName,
            1,
            1,
            fileName,
            DISP_BLOCKED,
            "NONE",
            null,
            "extension.author (plain-text source required)",
            "Compiled .tcp artifact detected; authorised plain-text .tdl/.txt source is required and decompilation is prohibited."
        );
        ExtensionDtos.TdlParityMatrixRowDto matrixRow = new ExtensionDtos.TdlParityMatrixRowDto(
            1,
            fileName + ":1",
            fileName,
            "Compiled TDL binary requires authorised source project before translation",
            "NONE",
            DISP_BLOCKED,
            "Authorised plain-text .tdl source submission",
            "Blocked until plain-text .tdl source is supplied",
            "NOT_RUN"
        );
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(DISP_TRANSLATED, 0);
        counts.put(DISP_REQUIRES_MAPPING, 0);
        counts.put(DISP_UNSUPPORTED, 0);
        counts.put(DISP_BLOCKED, 1);
        counts.put(DISP_NOT_APPLICABLE, 0);
        return new ParsedTdlAnalysis(
            true,
            false,
            false,
            "BLOCKED_DEPENDENCY",
            List.of(construct),
            List.of(matrixRow),
            counts,
            null
        );
    }

    private static ParsedTdlAnalysis analyzePlainTextTdl(
        String projectName,
        String fileName,
        byte[] rawBytes,
        Map<String, String> suppliedIncludes
    ) {
        String sourceText = decodeStrictUtf8(rawBytes);
        String namespace = toSafeNamespace(projectName);

        List<ExtensionDtos.TdlConstructDto> constructs = new ArrayList<>();
        List<ExtensionDtos.UpsertCustomFieldDefRequest> draftFields = new ArrayList<>();
        List<ExtensionDtos.UiAnchorCustomizationDto> draftAnchors = new ArrayList<>();
        List<ExtensionDtos.FeelRuleSpecDto> draftRules = new ArrayList<>();
        List<ExtensionDtos.ExtensionReportDefDto> draftReports = new ArrayList<>();
        Map<Integer, String> seenUdfIndexes = new HashMap<>();

        boolean[] flags = new boolean[2]; // [0] = nativeDll, [1] = unresolvedInclude
        parseSingleSourceUnit(
            fileName,
            sourceText,
            namespace,
            suppliedIncludes,
            constructs,
            draftFields,
            draftAnchors,
            draftRules,
            draftReports,
            seenUdfIndexes,
            flags
        );

        List<ExtensionDtos.TdlParityMatrixRowDto> matrix = new ArrayList<>(constructs.size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(DISP_TRANSLATED, 0);
        counts.put(DISP_REQUIRES_MAPPING, 0);
        counts.put(DISP_UNSUPPORTED, 0);
        counts.put(DISP_BLOCKED, 0);
        counts.put(DISP_NOT_APPLICABLE, 0);

        for (int i = 0; i < constructs.size(); i++) {
            ExtensionDtos.TdlConstructDto c = constructs.get(i);
            counts.compute(c.disposition(), (k, v) -> (v == null ? 0 : v) + 1);
            matrix.add(new ExtensionDtos.TdlParityMatrixRowDto(
                i + 1,
                c.sourceFile() + ":L" + c.startLine() + "-L" + c.endLine(),
                c.identifier(),
                c.constructType() + " (" + c.rawHeader() + ")",
                c.targetComponent(),
                c.disposition(),
                "Authorised source vs MoneyCraft " + c.targetComponent() + " fixture",
                c.explanation(),
                "NOT_RUN"
            ));
        }

        boolean hasBlocked = counts.getOrDefault(DISP_BLOCKED, 0) > 0;
        boolean hasUnmappedOrUnsupported = counts.getOrDefault(DISP_REQUIRES_MAPPING, 0) > 0
            || counts.getOrDefault(DISP_UNSUPPORTED, 0) > 0;
        String status = hasBlocked
            ? "BLOCKED_DEPENDENCY"
            : (hasUnmappedOrUnsupported ? "PARTIAL_PENDING_MAPPING" : "TRANSLATED_DRAFT");

        ExtensionDtos.ExtensionPackageDefinitionDto draftDef = new ExtensionDtos.ExtensionPackageDefinitionDto(
            ExtensionDtos.JSON_SCHEMA_2020_12,
            "1.0",
            namespace,
            UUID.nameUUIDFromBytes(("tdl-draft:" + namespace).getBytes(StandardCharsets.UTF_8)),
            projectName + " (TDL Imported Draft)",
            List.of("VOUCHER", "LEDGER"),
            List.copyOf(draftFields),
            List.copyOf(draftAnchors),
            List.copyOf(draftRules),
            List.of(),
            List.copyOf(draftReports),
            List.of(),
            List.of()
        );

        return new ParsedTdlAnalysis(
            false,
            flags[0],
            flags[1],
            status,
            List.copyOf(constructs),
            List.copyOf(matrix),
            counts,
            draftDef
        );
    }

    private static void parseSingleSourceUnit(
        String sourceFile,
        String text,
        String namespace,
        Map<String, String> suppliedIncludes,
        List<ExtensionDtos.TdlConstructDto> constructs,
        List<ExtensionDtos.UpsertCustomFieldDefRequest> draftFields,
        List<ExtensionDtos.UiAnchorCustomizationDto> draftAnchors,
        List<ExtensionDtos.FeelRuleSpecDto> draftRules,
        List<ExtensionDtos.ExtensionReportDefDto> draftReports,
        Map<Integer, String> seenUdfIndexes,
        boolean[] flags
    ) {
        String[] lines = text.split("\\r?\\n", -1);
        List<BlockSlice> blocks = new ArrayList<>();
        BlockSlice currentBlock = null;

        for (int idx = 0; idx < lines.length; idx++) {
            int lineNo = idx + 1;
            String rawLine = lines[idx];
            String trimmed = stripLineComment(rawLine).trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher incMatcher = INCLUDE_DIRECTIVE_PATTERN.matcher(trimmed);
            if (incMatcher.matches()) {
                if (currentBlock != null) {
                    blocks.add(currentBlock);
                    currentBlock = null;
                }
                String incTarget = incMatcher.group(1).trim().replace("\"", "");
                String incLower = incTarget.toLowerCase(Locale.ROOT);
                if (incLower.endsWith(".tcp")) {
                    flags[1] = true;
                    constructs.add(new ExtensionDtos.TdlConstructDto(
                        constructs.size() + 1,
                        "INCLUDE",
                        incTarget,
                        sourceFile,
                        lineNo,
                        lineNo,
                        trimmed,
                        DISP_BLOCKED,
                        "INCLUDE_RESOLVER",
                        null,
                        "extension.author",
                        "Included file '" + incTarget + "' is a compiled .tcp binary; plain-text .tdl source required."
                    ));
                } else if (suppliedIncludes.containsKey(incLower)) {
                    constructs.add(new ExtensionDtos.TdlConstructDto(
                        constructs.size() + 1,
                        "INCLUDE",
                        incTarget,
                        sourceFile,
                        lineNo,
                        lineNo,
                        trimmed,
                        DISP_TRANSLATED,
                        "EXTENSION_PACKAGE",
                        incTarget,
                        "extension.author",
                        "Included plain-text TDL unit '" + incTarget + "' resolved in-memory."
                    ));
                    parseSingleSourceUnit(
                        incTarget,
                        suppliedIncludes.get(incLower),
                        namespace,
                        Map.of(),
                        constructs,
                        draftFields,
                        draftAnchors,
                        draftRules,
                        draftReports,
                        seenUdfIndexes,
                        flags
                    );
                } else {
                    flags[1] = true;
                    constructs.add(new ExtensionDtos.TdlConstructDto(
                        constructs.size() + 1,
                        "INCLUDE",
                        incTarget,
                        sourceFile,
                        lineNo,
                        lineNo,
                        trimmed,
                        DISP_BLOCKED,
                        "INCLUDE_RESOLVER",
                        null,
                        "extension.author",
                        "Included file '" + incTarget + "' was not supplied in the intake archive."
                    ));
                }
                continue;
            }

            Matcher defMatcher = BRACKET_DEF_PATTERN.matcher(trimmed);
            if (defMatcher.find()) {
                if (currentBlock != null) {
                    blocks.add(currentBlock);
                }
                currentBlock = new BlockSlice(
                    defMatcher.group(1),
                    defMatcher.group(2).trim(),
                    defMatcher.group(3).trim(),
                    trimmed,
                    lineNo,
                    lineNo,
                    new ArrayList<>()
                );
            } else if (currentBlock != null) {
                currentBlock.bodyLines.add(new NumberedLine(lineNo, trimmed));
                currentBlock.endLine = lineNo;
            }
        }
        if (currentBlock != null) {
            blocks.add(currentBlock);
        }

        for (BlockSlice block : blocks) {
            classifyAndTranslateBlock(
                sourceFile,
                namespace,
                block,
                constructs,
                draftFields,
                draftAnchors,
                draftRules,
                draftReports,
                seenUdfIndexes,
                flags
            );
        }
    }

    private static void classifyAndTranslateBlock(
        String sourceFile,
        String namespace,
        BlockSlice block,
        List<ExtensionDtos.TdlConstructDto> constructs,
        List<ExtensionDtos.UpsertCustomFieldDefRequest> draftFields,
        List<ExtensionDtos.UiAnchorCustomizationDto> draftAnchors,
        List<ExtensionDtos.FeelRuleSpecDto> draftRules,
        List<ExtensionDtos.ExtensionReportDefDto> draftReports,
        Map<Integer, String> seenUdfIndexes,
        boolean[] flags
    ) {
        String kindUpper = block.kind.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String nameUpper = block.name.toUpperCase(Locale.ROOT).trim();
        String joinedBodyUpper = joinUpperBody(block);

        if (containsSerialEntitlementCheck(block.name, joinedBodyUpper)) {
            constructs.add(new ExtensionDtos.TdlConstructDto(
                constructs.size() + 1,
                kindUpper,
                block.name,
                sourceFile,
                block.startLine,
                block.endLine,
                block.rawHeader,
                DISP_NOT_APPLICABLE,
                "ZERO_LICENSING_EXCLUSION",
                null,
                "NONE",
                "Source serial/entitlement gate is excluded under MoneyCraft zero-licensing invariant (GEN-003)."
            ));
            return;
        }

        if (containsNativeComOrDll(kindUpper, joinedBodyUpper)) {
            flags[0] = true;
            constructs.add(new ExtensionDtos.TdlConstructDto(
                constructs.size() + 1,
                kindUpper,
                block.name,
                sourceFile,
                block.startLine,
                block.endLine,
                block.rawHeader,
                DISP_BLOCKED,
                "CONNECTOR_ADAPTER",
                null,
                "connector.manage",
                "External COM/DLL or direct host binary invocation is blocked; requires an admitted server-side connector profile."
            ));
            return;
        }

        if (joinedBodyUpper.contains("ODBC") || joinedBodyUpper.contains("SQL")) {
            flags[0] = true;
            constructs.add(new ExtensionDtos.TdlConstructDto(
                constructs.size() + 1,
                kindUpper,
                block.name,
                sourceFile,
                block.startLine,
                block.endLine,
                block.rawHeader,
                DISP_BLOCKED,
                "ODBC_PROJECTION_ADAPTER",
                null,
                "connector.manage",
                "ODBC/SQL data source dependency requires explicit inbound/outbound connector profile qualification (EXT-008)."
            ));
            return;
        }

        if ("SYSTEM".equals(kindUpper) && "UDF".equals(nameUpper)) {
            for (NumberedLine nl : block.bodyLines) {
                Matcher m = UDF_DECL_PATTERN.matcher(nl.text());
                if (m.matches()) {
                    String udfName = m.group(1).trim();
                    String udfType = m.group(2).trim().toUpperCase(Locale.ROOT);
                    int udfIndex = Integer.parseInt(m.group(3));
                    if (seenUdfIndexes.containsKey(udfIndex)
                        && !seenUdfIndexes.get(udfIndex).equalsIgnoreCase(udfName)) {
                        constructs.add(new ExtensionDtos.TdlConstructDto(
                            constructs.size() + 1,
                            "SYSTEM_UDF",
                            udfName,
                            sourceFile,
                            nl.lineNo(),
                            nl.lineNo(),
                            nl.text(),
                            DISP_REQUIRES_MAPPING,
                            "CUSTOM_FIELD",
                            null,
                            "extension.author",
                            "UDF index " + udfIndex + " collides with '" + seenUdfIndexes.get(udfIndex)
                                + "'; explicit UUID mapping required."
                        ));
                        continue;
                    }
                    seenUdfIndexes.put(udfIndex, udfName);
                    String mappedDataType = mapTdlUdfType(udfType);
                    UUID deterministicUuid = UUID.nameUUIDFromBytes(
                        (namespace + ":udf:" + udfIndex + ":" + udfName.toLowerCase(Locale.ROOT))
                            .getBytes(StandardCharsets.UTF_8)
                    );
                    draftFields.add(new ExtensionDtos.UpsertCustomFieldDefRequest(
                        namespace,
                        deterministicUuid,
                        "VOUCHER",
                        udfName,
                        udfName + " (UDF " + udfIndex + ")",
                        mappedDataType,
                        "EXACT_DECIMAL".equals(mappedDataType) ? 2 : null,
                        List.of(),
                        null,
                        "STANDARD",
                        "VOUCHER_HEADER_EXTRA",
                        false
                    ));
                    constructs.add(new ExtensionDtos.TdlConstructDto(
                        constructs.size() + 1,
                        "SYSTEM_UDF",
                        udfName,
                        sourceFile,
                        nl.lineNo(),
                        nl.lineNo(),
                        nl.text(),
                        DISP_TRANSLATED,
                        "CUSTOM_FIELD",
                        namespace + ":" + udfName,
                        "extension.author / extension.manage",
                        "Translated UDF index " + udfIndex + " (" + udfType + ") to MoneyCraft " + mappedDataType
                            + " custom field pending verification."
                    ));
                }
            }
            return;
        }

        if ("SYSTEM".equals(kindUpper) && ("FORMULA".equals(nameUpper) || "FORMULAE".equals(nameUpper))) {
            for (NumberedLine nl : block.bodyLines) {
                Matcher m = FORMULA_DECL_PATTERN.matcher(nl.text());
                if (m.matches()) {
                    String fName = m.group(1).trim();
                    String fExpr = m.group(2).trim();
                    String translatedFeel = tryTranslateSimpleFormulaToFeel(fExpr);
                    if (translatedFeel != null) {
                        draftRules.add(new ExtensionDtos.FeelRuleSpecDto(
                            fName,
                            "TDL Formula " + fName,
                            "VOUCHER",
                            "BLOCKING",
                            translatedFeel,
                            "TDL validation failed: " + fName,
                            100 + draftRules.size()
                        ));
                        constructs.add(new ExtensionDtos.TdlConstructDto(
                            constructs.size() + 1,
                            "SYSTEM_FORMULA",
                            fName,
                            sourceFile,
                            nl.lineNo(),
                            nl.lineNo(),
                            nl.text(),
                            DISP_TRANSLATED,
                            "FEEL_RULE",
                            fName,
                            "extension.author / extension.manage",
                            "Translated declarative formula into bounded FEEL expression '" + translatedFeel
                                + "' pending fixture verification."
                        ));
                    } else {
                        constructs.add(new ExtensionDtos.TdlConstructDto(
                            constructs.size() + 1,
                            "SYSTEM_FORMULA",
                            fName,
                            sourceFile,
                            nl.lineNo(),
                            nl.lineNo(),
                            nl.text(),
                            DISP_REQUIRES_MAPPING,
                            "FEEL_RULE",
                            null,
                            "extension.author",
                            "Formula '" + fExpr + "' references source object context requiring manual FEEL/DMN mapping."
                        ));
                    }
                }
            }
            return;
        }

        if ("FUNCTION".equals(kindUpper)
            || joinedBodyUpper.contains("WALK COLLECTION")
            || joinedBodyUpper.contains("NEW OBJECT")
            || joinedBodyUpper.contains("SAVE TARGET")
            || joinedBodyUpper.contains("WHILE :")) {
            constructs.add(new ExtensionDtos.TdlConstructDto(
                constructs.size() + 1,
                "PROCEDURAL_" + kindUpper,
                block.name,
                sourceFile,
                block.startLine,
                block.endLine,
                block.rawHeader,
                DISP_UNSUPPORTED,
                "WORKFLOW_ACTION",
                null,
                "extension.manage",
                "Imperative procedural loops and state mutations have no automatic runtime execution in MoneyCraft; requires declarative reconstruction."
            ));
            return;
        }

        if ("MENU".equals(kindUpper)
            || "FORM".equals(kindUpper)
            || "PART".equals(kindUpper)
            || "LINE".equals(kindUpper)
            || "FIELD".equals(kindUpper)) {
            boolean isKnownAnchor = block.modifier.equals("#")
                && (nameUpper.contains("GATEWAY")
                    || nameUpper.contains("VCH")
                    || nameUpper.contains("VOUCHER")
                    || nameUpper.contains("LED")
                    || nameUpper.contains("NARRATION"));
            if (isKnownAnchor || "FIELD".equals(kindUpper)) {
                String anchorId = kindUpper + "_" + block.name.replaceAll("[^A-Za-z0-9_]", "_");
                draftAnchors.add(new ExtensionDtos.UiAnchorCustomizationDto(
                    anchorId,
                    nameUpper.contains("LED") ? "LEDGER_EDITOR" : "VOUCHER_EDITOR",
                    "ADD_SECTION",
                    List.of(),
                    null,
                    null
                ));
                constructs.add(new ExtensionDtos.TdlConstructDto(
                    constructs.size() + 1,
                    (block.modifier.isEmpty() ? "" : "MODIFIER_") + kindUpper,
                    block.name,
                    sourceFile,
                    block.startLine,
                    block.endLine,
                    block.rawHeader,
                    DISP_TRANSLATED,
                    "UI_ANCHOR",
                    anchorId,
                    "extension.author / extension.manage",
                    "Mapped TDL screen definition to declarative UI anchor '" + anchorId + "' pending verification."
                ));
            } else {
                constructs.add(new ExtensionDtos.TdlConstructDto(
                    constructs.size() + 1,
                    (block.modifier.isEmpty() ? "" : "MODIFIER_") + kindUpper,
                    block.name,
                    sourceFile,
                    block.startLine,
                    block.endLine,
                    block.rawHeader,
                    DISP_REQUIRES_MAPPING,
                    "UI_ANCHOR",
                    null,
                    "extension.author",
                    "Source UI container '" + block.name + "' requires explicit mapping to a published MoneyCraft screen anchor."
                ));
            }
            return;
        }

        if ("REPORT".equals(kindUpper)) {
            String repCode = block.name.replaceAll("[^A-Za-z0-9_]", "_");
            draftReports.add(new ExtensionDtos.ExtensionReportDefDto(
                repCode,
                block.name,
                "VOUCHERS",
                List.of("voucherDate", "voucherNumber", "voucherType", "totalDebit"),
                Map.of()
            ));
            constructs.add(new ExtensionDtos.TdlConstructDto(
                constructs.size() + 1,
                "REPORT",
                block.name,
                sourceFile,
                block.startLine,
                block.endLine,
                block.rawHeader,
                DISP_TRANSLATED,
                "DECLARATIVE_REPORT",
                repCode,
                "company.read",
                "Translated report shell '" + block.name + "' to authorised VOUCHERS projection descriptor pending column verification."
            ));
            return;
        }

        constructs.add(new ExtensionDtos.TdlConstructDto(
            constructs.size() + 1,
            kindUpper,
            block.name,
            sourceFile,
            block.startLine,
            block.endLine,
            block.rawHeader,
            DISP_REQUIRES_MAPPING,
            "EXTENSION_WORKBENCH",
            null,
            "extension.author",
            "Construct '" + block.rawHeader + "' requires analyst mapping to an admitted MoneyCraft capability."
        ));
    }

    private static boolean containsSerialEntitlementCheck(String name, String upperBody) {
        String uName = name.toUpperCase(Locale.ROOT);
        return uName.contains("SERIALCHECK")
            || uName.contains("ENTITLEMENT")
            || upperBody.contains("$$SERIALNUMBER")
            || upperBody.contains("$$ISEDUCATIONAL")
            || upperBody.contains("SERIALNUMBER");
    }

    private static boolean containsNativeComOrDll(String kindUpper, String upperBody) {
        return kindUpper.contains("COM")
            || upperBody.contains("CALL DLL")
            || upperBody.contains("COM INTERFACE")
            || upperBody.contains(".DLL")
            || upperBody.contains("EXEC COMMAND");
    }

    private static String mapTdlUdfType(String rawType) {
        return switch (rawType) {
            case "AMOUNT", "NUMBER", "QUANTITY", "RATE" -> "EXACT_DECIMAL";
            case "DATE" -> "DATE";
            case "LOGICAL" -> "BOOLEAN";
            default -> "TEXT";
        };
    }

    private static String tryTranslateSimpleFormulaToFeel(String expr) {
        if (expr.contains("$$") && !expr.contains("$$IsEmpty")) {
            return null;
        }
        String normalized = expr
            .replace("$Amount", "totalDebit")
            .replace("$Narration", "narration")
            .replace("$VoucherTypeName", "voucherType")
            .replace("$$IsEmpty:", "isEmpty")
            .replace("NOT ", "not ");
        if (normalized.contains("$") || normalized.contains("#") || normalized.contains("/")) {
            return null;
        }
        return normalized.trim();
    }

    private static String decodeStrictUtf8(byte[] rawBytes) {
        try {
            int offset = (rawBytes.length >= 3
                && (rawBytes[0] & 0xFF) == 0xEF
                && (rawBytes[1] & 0xFF) == 0xBB
                && (rawBytes[2] & 0xFF) == 0xBF) ? 3 : 0;
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes, offset, rawBytes.length - offset));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-TDL-ENCODING-AMBIGUOUS",
                "Ambiguous or Non-UTF-8 TDL Encoding",
                "TDL source must be valid UTF-8 text; convert legacy encodings explicitly before intake."
            );
        }
    }

    private static String stripLineComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuote = !inQuote;
            } else if (!inQuote && (ch == ';' || (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/'))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String joinUpperBody(BlockSlice block) {
        StringBuilder sb = new StringBuilder();
        for (NumberedLine nl : block.bodyLines) {
            sb.append(nl.text()).append('\n');
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static String deriveProjectName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        return stem.isBlank() ? "Imported TDL Project" : stem;
    }

    private static String toSafeNamespace(String projectName) {
        String cleaned = projectName.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (cleaned.length() < 2) {
            cleaned = "tdl-" + cleaned + "ext";
        }
        if (!Character.isLetterOrDigit(cleaned.charAt(0))) {
            cleaned = "tdl-" + cleaned;
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static Map<String, String> normalizeIncludesMap(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                map.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return Map.copyOf(map);
    }

    private ExtensionDtos.TdlImportProjectDto mapProjectRow(ResultSet rs) throws SQLException {
        return new ExtensionDtos.TdlImportProjectDto(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("project_name"),
            rs.getString("file_name"),
            rs.getString("source_version_claim"),
            JcsCanonicalizer.toHex(rs.getBytes("source_sha256")),
            rs.getBoolean("has_compiled_tcp_block"),
            rs.getBoolean("has_native_dll_block"),
            rs.getBoolean("has_unresolved_includes"),
            rs.getString("status"),
            rs.getObject("draft_extension_id", UUID.class),
            parseCounts(rs.getString("summary_counts_json")),
            parseConstructs(rs.getString("constructs_json")),
            parseParityMatrix(rs.getString("parity_matrix_json")),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private Map<String, Integer> parseCounts(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Integer>>() { });
        } catch (JacksonException ignored) {
            return Map.of();
        }
    }

    private List<ExtensionDtos.TdlConstructDto> parseConstructs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                json,
                new TypeReference<List<ExtensionDtos.TdlConstructDto>>() { }
            );
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    private List<ExtensionDtos.TdlParityMatrixRowDto> parseParityMatrix(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                json,
                new TypeReference<List<ExtensionDtos.TdlParityMatrixRowDto>>() { }
            );
        } catch (JacksonException ignored) {
            return List.of();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize TDL project JSON", ex);
        }
    }

    private record NumberedLine(int lineNo, String text) {
    }

    private static final class BlockSlice {
        private final String modifier;
        private final String kind;
        private final String name;
        private final String rawHeader;
        private final int startLine;
        private int endLine;
        private final List<NumberedLine> bodyLines;

        BlockSlice(
            String modifier,
            String kind,
            String name,
            String rawHeader,
            int startLine,
            int endLine,
            List<NumberedLine> bodyLines
        ) {
            this.modifier = modifier;
            this.kind = kind;
            this.name = name;
            this.rawHeader = rawHeader;
            this.startLine = startLine;
            this.endLine = endLine;
            this.bodyLines = bodyLines;
        }
    }

    private record ParsedTdlAnalysis(
        boolean hasCompiledTcpBlock,
        boolean hasNativeDllBlock,
        boolean hasUnresolvedIncludes,
        String projectStatus,
        List<ExtensionDtos.TdlConstructDto> constructs,
        List<ExtensionDtos.TdlParityMatrixRowDto> parityMatrix,
        Map<String, Integer> summaryCounts,
        ExtensionDtos.ExtensionPackageDefinitionDto draftDefinition
    ) {
    }
}
