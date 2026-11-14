package com.raster.moneycraft.foundation.auth;

import com.raster.moneycraft.foundation.authority.InstallationSecurityState;
import com.raster.moneycraft.foundation.security.CompanyGrantInfo;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.SystemRole;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SystemStatusResponse(
        InstallationSecurityState securityState,
        long fenceEpoch,
        String version,
        String quarantineReason
    ) {
    }

    public record InitialiseInstallationRequest(
        String installationLabel,
        String ownerUsername,
        String ownerDisplayName,
        String password
    ) {
    }

    public record InitialiseInstallationResponse(
        UUID installationId,
        AuthenticatedPrincipalDto ownerPrincipal,
        String recoveryReceiptCode
    ) {
    }

    public record CompanyGrantSummaryDto(
        UUID grantId,
        UUID companyId,
        CompanyRole roleCode,
        List<String> voucherTypeAllowlist,
        Integer backdatedDaysLimit,
        boolean exportPermitted
    ) {
        public static CompanyGrantSummaryDto from(CompanyGrantInfo info) {
            return new CompanyGrantSummaryDto(
                info.grantId(),
                info.companyId(),
                info.roleCode(),
                info.voucherTypeAllowlist(),
                info.backdatedDaysLimit(),
                info.exportPermitted()
            );
        }
    }

    public record AuthenticatedPrincipalDto(
        UUID principalId,
        String username,
        String displayName,
        List<SystemRole> systemRoles,
        List<CompanyGrantSummaryDto> companyGrants,
        boolean passkeyEnrolled,
        String csrfToken
    ) {
        public static AuthenticatedPrincipalDto from(MoneyCraftPrincipal principal, String csrfToken) {
            List<CompanyGrantSummaryDto> mappedGrants = principal.companyGrants().stream()
                .map(CompanyGrantSummaryDto::from)
                .toList();
            return new AuthenticatedPrincipalDto(
                principal.principalId(),
                principal.username(),
                principal.displayName(),
                principal.systemRoles(),
                mappedGrants,
                principal.passkeyEnrolled(),
                csrfToken
            );
        }
    }

    public record LoginRequest(String username, String password) {
    }

    public record PasskeyRegisterOptionsRequest(String label) {
    }

    public record PasskeyRpDto(String name, String id) {
    }

    public record PasskeyUserDto(String id, String name, String displayName) {
    }

    public record PasskeyCredParamDto(String type, int alg) {
    }

    public record PasskeyCredentialDescriptorDto(String id, String type) {
    }

    public record PasskeyCreationOptionsResponse(
        String challenge,
        PasskeyRpDto rp,
        PasskeyUserDto user,
        List<PasskeyCredParamDto> pubKeyCredParams,
        long timeout,
        String attestation,
        List<PasskeyCredentialDescriptorDto> excludeCredentials
    ) {
    }

    public record PasskeyRegisterVerifyRequest(
        String label,
        String credentialId,
        String rawId,
        String type,
        String clientDataJSON,
        String attestationObject
    ) {
    }

    public record PasskeyAuthOptionsRequest(String username) {
    }

    public record PasskeyRequestOptionsResponse(
        String challenge,
        String rpId,
        long timeout,
        String userVerification,
        List<PasskeyCredentialDescriptorDto> allowCredentials
    ) {
    }

    public record PasskeyAuthVerifyRequest(
        String credentialId,
        String rawId,
        String type,
        String clientDataJSON,
        String authenticatorData,
        String signature,
        String userHandle
    ) {
    }

    public record OidcAuthorizeResponse(String authorizationUrl, String state) {
    }

    public record OidcCallbackRequest(String code, String state) {
    }

    public record CreateOidcIssuerRequest(
        String registrationId,
        String displayName,
        String issuerUri,
        String authorizationUri,
        String tokenUri,
        String jwksUri,
        String userinfoUri,
        String clientId,
        String clientSecret,
        String scopes,
        String linkPrincipalUsername,
        String linkSubject
    ) {
    }

    public record OidcIssuerDto(
        UUID issuerId,
        String registrationId,
        String displayName,
        String issuerUri,
        String clientId,
        boolean pkceRequired
    ) {
    }

    public record CreatePrincipalRequest(
        String username,
        String displayName,
        String email,
        String password,
        List<SystemRole> systemRoles
    ) {
    }

    public record AdminPrincipalRecordDto(
        UUID id,
        String username,
        String displayName,
        List<SystemRole> systemRoles,
        boolean active
    ) {
    }

    public record AssignCompanyGrantRequest(
        UUID companyId,
        CompanyRole roleCode,
        List<String> voucherTypeAllowlist,
        Integer backdatedDaysLimit,
        Boolean exportPermitted
    ) {
    }

    public record CreateServiceKeyRequest(
        String label,
        List<UUID> companyIds,
        List<String> endpointAllowlist,
        OffsetDateTime expiresAt
    ) {
    }

    public record CreatedServiceKeyResponse(
        UUID keyId,
        String label,
        String keyPrefix,
        String rawSecretOnce,
        OffsetDateTime expiresAt
    ) {
    }

    public record AuditChainVerifyResponseDto(
        boolean verified,
        long checkedEventCount,
        String tailHashSha256,
        String verifiedAt,
        String failureDetail
    ) {
    }

    public record EnterQuarantineRequest(String reason) {
    }

    public record RecoverQuarantineRequest(String recoveryReceipt, String recoveryReceiptCode) {
        public String resolvedReceipt() {
            if (recoveryReceipt != null && !recoveryReceipt.isBlank()) {
                return recoveryReceipt.trim();
            }
            if (recoveryReceiptCode != null && !recoveryReceiptCode.isBlank()) {
                return recoveryReceiptCode.trim();
            }
            return "";
        }
    }
}
