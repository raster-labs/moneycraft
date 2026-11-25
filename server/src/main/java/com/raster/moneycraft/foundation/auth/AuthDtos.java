package com.raster.moneycraft.foundation.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        boolean superAdmin,
        String csrfToken
    ) {
        public AuthenticatedPrincipalDto(
            UUID principalId,
            String username,
            String displayName,
            List<SystemRole> systemRoles,
            List<CompanyGrantSummaryDto> companyGrants,
            boolean passkeyEnrolled,
            String csrfToken
        ) {
            this(
                principalId,
                username,
                displayName,
                systemRoles,
                companyGrants,
                passkeyEnrolled,
                false,
                csrfToken
            );
        }

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
                principal.superAdmin(),
                csrfToken
            );
        }
    }

    public record LoginRequest(String username, String password) {
    }

    public record PasswordStepUpRequest(
        String password,
        UUID targetProfileId,
        String action,
        Long expectedRevision
    ) {
    }

    public record PasswordStepUpResponse(
        UUID stepUpIntentId,
        OffsetDateTime expiresAt,
        String action,
        UUID targetProfileId,
        Long expectedRevision
    ) {
    }

    public record SuperAdminDesignationRequest(String reason) {
    }

    public record SuperAdminDesignationDto(
        UUID principalId,
        String username,
        String state,
        String ceremonySource,
        String ceremonyReason,
        OffsetDateTime designatedAt,
        OffsetDateTime revokedAt
    ) {
    }

    public record NativeAuthorizeApproveRequest(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("redirect_uri") String redirectUri,
        String scope,
        String state,
        String nonce,
        @JsonProperty("code_challenge") String codeChallenge,
        @JsonProperty("code_challenge_method") String codeChallengeMethod,
        String nativeAuthorizeQuery
    ) {
    }

    public record NativeAuthorizeApproveResponse(
        String redirectUri,
        String code,
        String state
    ) {
    }

    public record NativeTokenResponseDto(
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("id_token") String idToken,
        @JsonProperty("expires_in") long expiresIn,
        String scope
    ) {
    }

    public record NativeOidcDiscoveryDto(
        String issuer,
        @JsonProperty("authorization_endpoint") String authorizationEndpoint,
        @JsonProperty("token_endpoint") String tokenEndpoint,
        @JsonProperty("revocation_endpoint") String revocationEndpoint,
        @JsonProperty("jwks_uri") String jwksUri,
        @JsonProperty("response_types_supported") List<String> responseTypesSupported,
        @JsonProperty("grant_types_supported") List<String> grantTypesSupported,
        @JsonProperty("code_challenge_methods_supported") List<String> codeChallengeMethodsSupported,
        @JsonProperty("token_endpoint_auth_methods_supported") List<String> tokenEndpointAuthMethodsSupported,
        @JsonProperty("id_token_signing_alg_values_supported") List<String> idTokenSigningAlgValuesSupported,
        @JsonProperty("public_key_pem") String publicKeyPem
    ) {
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

    public record PublicOidcIssuerDto(
        UUID id,
        String registrationId,
        String displayName
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
        List<String> permissions,
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
