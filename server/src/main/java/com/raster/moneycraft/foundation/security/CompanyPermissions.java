package com.raster.moneycraft.foundation.security;

import java.util.Set;

/**
 * Canonical company-scoped operation and Increment X permission constants.
 */
public final class CompanyPermissions {

    public static final String COMPANY_READ = "company.read";

    public static final String MASTER_READ = "master.read";
    public static final String MASTER_CREATE = "master.create";
    public static final String MASTER_CHANGE = "master.change";

    public static final String VOUCHER_READ = "voucher.read";
    public static final String VOUCHER_CREATE = "voucher.create";
    public static final String VOUCHER_CHANGE = "voucher.change";
    public static final String VOUCHER_APPROVE = "voucher.approve";
    public static final String VOUCHER_POST = "voucher.post";
    public static final String VOUCHER_REVERSE = "voucher.reverse";

    public static final String REPORT_READ = "report.read";
    public static final String EXPORT = "export";

    public static final String FIELD_PRIVATE_READ = "field.private.read";
    public static final String FIELD_PRIVATE_CHANGE = "field.private.change";

    // Increment X (EXT-001..008, TDL-001..004, SHEET-001..008) permission constants
    public static final String EXTENSION_AUTHOR = "extension.author";
    public static final String EXTENSION_MANAGE = "extension.manage";
    public static final String IMPORT_PREPARE = "import.prepare";
    public static final String IMPORT_READ = "import.read";
    public static final String IMPORT_COMMIT = "import.commit";
    public static final String IMPORT_TEMPLATE_MANAGE = "import.template.manage";
    public static final String CONNECTOR_MANAGE = "connector.manage";

    /**
     * Exact Document 18 / Document 47 §3 allowlist of company-use operations eligible for
     * OIDC PROVIDER groupMappings. Administrative, recovery, migration, wildcard, and Increment X
     * permissions are excluded from provider group mappings.
     */
    public static final Set<String> ALLOWED_PROVIDER_GROUP_OPERATIONS = Set.of(
        COMPANY_READ,
        MASTER_READ,
        MASTER_CREATE,
        MASTER_CHANGE,
        VOUCHER_READ,
        VOUCHER_CREATE,
        VOUCHER_CHANGE,
        VOUCHER_APPROVE,
        VOUCHER_POST,
        VOUCHER_REVERSE,
        REPORT_READ,
        EXPORT,
        FIELD_PRIVATE_READ,
        FIELD_PRIVATE_CHANGE
    );

    public static final Set<String> ALL_KNOWN_COMPANY_PERMISSIONS = Set.of(
        COMPANY_READ,
        MASTER_READ,
        MASTER_CREATE,
        MASTER_CHANGE,
        VOUCHER_READ,
        VOUCHER_CREATE,
        VOUCHER_CHANGE,
        VOUCHER_APPROVE,
        VOUCHER_POST,
        VOUCHER_REVERSE,
        REPORT_READ,
        EXPORT,
        FIELD_PRIVATE_READ,
        FIELD_PRIVATE_CHANGE,
        EXTENSION_AUTHOR,
        EXTENSION_MANAGE,
        IMPORT_PREPARE,
        IMPORT_READ,
        IMPORT_COMMIT,
        IMPORT_TEMPLATE_MANAGE,
        CONNECTOR_MANAGE
    );

    private CompanyPermissions() {
    }
}
