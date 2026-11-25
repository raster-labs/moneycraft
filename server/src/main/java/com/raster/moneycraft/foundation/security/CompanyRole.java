package com.raster.moneycraft.foundation.security;

import java.util.Set;

public enum CompanyRole {
    COMPANY_ADMIN(CompanyPermissions.ALL_KNOWN_COMPANY_PERMISSIONS),
    AUDITOR_APPROVER(Set.of(
        CompanyPermissions.COMPANY_READ,
        CompanyPermissions.MASTER_READ,
        CompanyPermissions.VOUCHER_READ,
        CompanyPermissions.VOUCHER_APPROVE,
        CompanyPermissions.VOUCHER_POST,
        CompanyPermissions.REPORT_READ,
        CompanyPermissions.EXPORT,
        CompanyPermissions.IMPORT_READ
    )),
    SENIOR_ACCOUNTANT(Set.of(
        CompanyPermissions.COMPANY_READ,
        CompanyPermissions.MASTER_READ,
        CompanyPermissions.MASTER_CREATE,
        CompanyPermissions.MASTER_CHANGE,
        CompanyPermissions.VOUCHER_READ,
        CompanyPermissions.VOUCHER_CREATE,
        CompanyPermissions.VOUCHER_CHANGE,
        CompanyPermissions.VOUCHER_APPROVE,
        CompanyPermissions.VOUCHER_POST,
        CompanyPermissions.VOUCHER_REVERSE,
        CompanyPermissions.REPORT_READ,
        CompanyPermissions.EXPORT,
        CompanyPermissions.IMPORT_PREPARE,
        CompanyPermissions.IMPORT_READ,
        CompanyPermissions.IMPORT_COMMIT,
        CompanyPermissions.IMPORT_TEMPLATE_MANAGE
    )),
    DATA_ENTRY_OPERATOR(Set.of(
        CompanyPermissions.COMPANY_READ,
        CompanyPermissions.MASTER_READ,
        CompanyPermissions.VOUCHER_READ,
        CompanyPermissions.VOUCHER_CREATE,
        CompanyPermissions.VOUCHER_CHANGE,
        CompanyPermissions.IMPORT_PREPARE,
        CompanyPermissions.IMPORT_READ
    )),
    INVENTORY_CLERK(Set.of(
        CompanyPermissions.COMPANY_READ,
        CompanyPermissions.MASTER_READ,
        CompanyPermissions.MASTER_CREATE,
        CompanyPermissions.MASTER_CHANGE,
        CompanyPermissions.VOUCHER_READ,
        CompanyPermissions.VOUCHER_CREATE
    )),
    READ_ONLY_VIEWER(Set.of(
        CompanyPermissions.COMPANY_READ,
        CompanyPermissions.MASTER_READ,
        CompanyPermissions.VOUCHER_READ,
        CompanyPermissions.REPORT_READ
    ));

    public static final String PERM_EXTENSION_AUTHOR = CompanyPermissions.EXTENSION_AUTHOR;
    public static final String PERM_EXTENSION_MANAGE = CompanyPermissions.EXTENSION_MANAGE;
    public static final String PERM_IMPORT_PREPARE = CompanyPermissions.IMPORT_PREPARE;
    public static final String PERM_IMPORT_READ = CompanyPermissions.IMPORT_READ;
    public static final String PERM_IMPORT_COMMIT = CompanyPermissions.IMPORT_COMMIT;
    public static final String PERM_IMPORT_TEMPLATE_MANAGE = CompanyPermissions.IMPORT_TEMPLATE_MANAGE;
    public static final String PERM_CONNECTOR_MANAGE = CompanyPermissions.CONNECTOR_MANAGE;

    private final Set<String> defaultPermissionSet;

    CompanyRole(Set<String> defaultPermissionSet) {
        this.defaultPermissionSet = Set.copyOf(defaultPermissionSet);
    }

    public Set<String> defaultPermissions() {
        return defaultPermissionSet;
    }
}
