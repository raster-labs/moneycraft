package com.raster.moneycraft.desktop.client;

import com.raster.moneycraft.desktop.auth.AdmittedNativeIssuerProfile;
import com.raster.moneycraft.desktop.auth.NativePkceAuthClient;
import com.raster.moneycraft.desktop.auth.StoredNativeCredentials;
import com.raster.moneycraft.desktop.client.dto.CompanyDto;
import com.raster.moneycraft.desktop.keyboard.DesktopCommandRegistry;
import com.raster.moneycraft.desktop.preferences.DesktopPreferencesManager;
import com.raster.moneycraft.desktop.preferences.UiPreferencesEnvelopeDto;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * Coordinates authenticated session state, {@link ContextEpochGuard} stale-response protection,
 * {@link NativePkceAuthClient} external-browser OAuth2/PKCE sign-in, {@link DesktopPreferencesManager}
 * account preference hydration, and non-EDT {@link DesktopApiClient} dispatch (Stage A &amp; B,
 * ITER110-021..027).
 */
public final class DesktopSessionCoordinator implements AutoCloseable {

    private final DesktopApiClient apiClient;
    private final NativePkceAuthClient authClient;
    private final AdmittedNativeIssuerProfile issuerProfile;
    private final ContextEpochGuard epochGuard;
    private final DesktopPreferencesManager preferencesManager;
    private final DesktopCommandRegistry commandRegistry;

    private volatile String authenticatedPrincipalId;
    private volatile String authenticatedDisplayName;
    private volatile CompanyDto activeCompany;
    private volatile String activeCompanyEtag;
    private volatile UUID activeVoucherId;
    private volatile String activeVoucherEtag;
    private volatile LocalDate activeVoucherDate = LocalDate.of(2026, 4, 15);
    private volatile LocalDate activePeriodFrom = LocalDate.of(2026, 4, 1);
    private volatile LocalDate activePeriodTo = LocalDate.of(2027, 3, 31);
    private volatile MutationIntention lastUncertainIntention;
    private volatile boolean uncertainStatusChecked;

    public DesktopSessionCoordinator(
        DesktopApiClient apiClient,
        NativePkceAuthClient authClient,
        AdmittedNativeIssuerProfile issuerProfile,
        ContextEpochGuard epochGuard,
        DesktopPreferencesManager preferencesManager,
        DesktopCommandRegistry commandRegistry
    ) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient must not be null");
        this.authClient = authClient;
        this.issuerProfile = issuerProfile;
        this.epochGuard = Objects.requireNonNull(epochGuard, "epochGuard must not be null");
        this.preferencesManager = Objects.requireNonNull(preferencesManager, "preferencesManager must not be null");
        this.commandRegistry = Objects.requireNonNull(commandRegistry, "commandRegistry must not be null");
    }

    public DesktopApiClient apiClient() {
        return apiClient;
    }

    public Optional<NativePkceAuthClient> authClient() {
        return Optional.ofNullable(authClient);
    }

    public Optional<AdmittedNativeIssuerProfile> issuerProfile() {
        return Optional.ofNullable(issuerProfile);
    }

    public ContextEpochGuard epochGuard() {
        return epochGuard;
    }

    public Optional<String> authenticatedPrincipalId() {
        return Optional.ofNullable(authenticatedPrincipalId);
    }

    public Optional<String> authenticatedDisplayName() {
        return Optional.ofNullable(authenticatedDisplayName);
    }

    public Optional<CompanyDto> activeCompany() {
        return Optional.ofNullable(activeCompany);
    }

    public Optional<String> activeCompanyEtag() {
        return Optional.ofNullable(activeCompanyEtag);
    }

    public Optional<UUID> activeVoucherId() {
        return Optional.ofNullable(activeVoucherId);
    }

    public Optional<String> activeVoucherEtag() {
        return Optional.ofNullable(activeVoucherEtag);
    }

    public void setActiveVoucherContext(UUID voucherId, String etag) {
        this.activeVoucherId = voucherId;
        this.activeVoucherEtag = etag;
    }

    public void clearActiveVoucherContext() {
        this.activeVoucherId = null;
        this.activeVoucherEtag = null;
    }

    public LocalDate activeVoucherDate() {
        return activeVoucherDate;
    }

    public void setActiveVoucherDate(LocalDate voucherDate) {
        this.activeVoucherDate = Objects.requireNonNull(voucherDate, "voucherDate must not be null");
    }

    public LocalDate activePeriodFrom() {
        return activePeriodFrom;
    }

    public LocalDate activePeriodTo() {
        return activePeriodTo;
    }

    public void setActivePeriod(LocalDate fromDate, LocalDate toDate) {
        Objects.requireNonNull(fromDate, "fromDate must not be null");
        Objects.requireNonNull(toDate, "toDate must not be null");
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("Period end date must not precede period start date.");
        }
        this.activePeriodFrom = fromDate;
        this.activePeriodTo = toDate;
    }

    public Optional<MutationIntention> lastUncertainIntention() {
        return Optional.ofNullable(lastUncertainIntention);
    }

    public boolean isUncertainStatusChecked() {
        return uncertainStatusChecked;
    }

    public void recordUncertainIntention(MutationIntention intention) {
        this.lastUncertainIntention = Objects.requireNonNull(intention, "intention must not be null");
        this.uncertainStatusChecked = false;
    }

    public void markUncertainStatusChecked() {
        this.uncertainStatusChecked = true;
    }

    public void clearUncertainIntention() {
        this.lastUncertainIntention = null;
        this.uncertainStatusChecked = false;
    }

    public void selectCompany(CompanyDto company, String etag) {
        this.activeCompany = company;
        this.activeCompanyEtag = etag;
        if (company != null) {
            epochGuard.onCompanySwitched(company.id());
            if (company.financialYearFrom() != null) {
                this.activePeriodFrom = company.financialYearFrom();
                this.activePeriodTo = company.financialYearFrom().plusYears(1).minusDays(1);
                this.activeVoucherDate = company.financialYearFrom();
            }
        } else {
            epochGuard.onCompanySwitched(null);
        }
    }

    /**
     * Completes external-browser OAuth2 + PKCE S256 sign-in, hydrates {@code /api/v1/auth/me}
     * and {@code /api/v1/me/ui-preferences}, and loads the authorised company list off the EDT.
     */
    public CompletableFuture<List<CompanyDto>> signInAndHydrateAsync(Duration callbackTimeout) {
        if (authClient == null || issuerProfile == null) {
            return hydrateSessionFromCurrentTokenAsync();
        }
        return authClient.signInAsync(issuerProfile, callbackTimeout)
            .thenCompose(creds -> {
                bindCredentials(creds);
                return hydrateSessionFromCurrentTokenAsync();
            });
    }

    public CompletableFuture<List<CompanyDto>> hydrateSessionFromCurrentTokenAsync() {
        return apiClient.getAuthenticatedMe()
            .handle((meNode, err) -> {
                if (meNode != null) {
                    JsonNode pidNode = meNode.get("principalId");
                    String pid = pidNode != null && !pidNode.isNull()
                        ? pidNode.asString(null)
                        : null;
                    if (pid != null && !pid.isBlank()) {
                        this.authenticatedPrincipalId = pid;
                        commandRegistry.bindAuthenticatedOwner(pid);
                        epochGuard.onPrincipalChanged(pid);
                    }
                    JsonNode nameNode = meNode.get("displayName");
                    if (nameNode != null && !nameNode.isNull()) {
                        this.authenticatedDisplayName = nameNode.asString(null);
                    }
                }
                return meNode;
            })
            .thenCompose(ignored -> apiClient.getAccountUiPreferences()
                .handle((VersionedResponse<UiPreferencesEnvelopeDto> prefsResp, Throwable prefsErr) -> {
                    if (prefsResp != null && prefsResp.body() != null) {
                        preferencesManager.onAuthenticatedAccountLoaded(prefsResp);
                    }
                    return prefsResp;
                }))
            .thenCompose(ignored -> apiClient.listCompanies());
    }

    public void signInAndDeliverOnEdt(
        Duration callbackTimeout,
        Consumer<List<CompanyDto>> onCompaniesLoaded,
        Consumer<Throwable> onError
    ) {
        DesktopApiClient.deliverOnEdt(
            signInAndHydrateAsync(callbackTimeout),
            onCompaniesLoaded,
            onError
        );
    }

    public void signOut() {
        if (authClient != null && issuerProfile != null) {
            authClient.logout(issuerProfile);
        }
        apiClient.clearTransientStateOnLogout();
        preferencesManager.onLogoutOrSessionExpired();
        commandRegistry.clearPreferencesOnLogout();
        this.authenticatedPrincipalId = null;
        this.authenticatedDisplayName = null;
        this.activeCompany = null;
        this.activeCompanyEtag = null;
        this.activeVoucherId = null;
        this.activeVoucherEtag = null;
        this.lastUncertainIntention = null;
        this.uncertainStatusChecked = false;
        epochGuard.onPrincipalChanged(null);
        epochGuard.onCompanySwitched(null);
    }

    /**
     * Handles OS window close under S-02 and S-06: on Windows/Linux where closing the primary
     * window terminates the session, wipes in-memory credentials; on macOS where closing hides
     * the window, preserves in-memory session until Dock reopen or token expiry.
     */
    public void onWindowClosed(boolean applicationTerminated) {
        if (applicationTerminated) {
            signOut();
        }
    }

    /**
     * Verifies session continuity when a hidden desktop window is reopened (e.g. macOS Dock reopen
     * under a locked OS keychain). If the in-memory access token has expired and no persistent
     * refresh token is available in the locked vault, clears stale state and requires interactive
     * browser re-authentication (S-06, AT-ITER110-023/025).
     *
     * @return true if the authenticated session is still valid; false if interactive re-auth is required
     */
    public boolean onWindowReopened() {
        if (authClient == null) {
            return authenticatedPrincipalId != null;
        }
        if (authClient.currentAccessToken().isPresent()) {
            return true;
        }
        if (issuerProfile != null) {
            try {
                StoredNativeCredentials rotated = authClient.rotateRefreshToken(issuerProfile);
                bindCredentials(rotated);
                return true;
            } catch (Exception ignored) {
                // Locked/unavailable OS vault or expired refresh token: fall through to clean sign-out!
            }
        }
        signOut();
        return false;
    }

    private void bindCredentials(StoredNativeCredentials creds) {
        if (creds != null && creds.subject() != null && !creds.subject().isBlank()) {
            this.authenticatedPrincipalId = creds.subject();
            commandRegistry.bindAuthenticatedOwner(creds.subject());
            epochGuard.onPrincipalChanged(creds.subject());
        }
    }

    @Override
    public void close() {
        if (authClient != null) {
            authClient.close();
        }
        apiClient.close();
    }
}
