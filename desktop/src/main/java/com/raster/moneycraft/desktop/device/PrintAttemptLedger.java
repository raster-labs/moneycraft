package com.raster.moneycraft.desktop.device;

import com.raster.moneycraft.desktop.client.DesktopJcsCanonicalizer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.type.TypeReference;

/**
 * AES-256-GCM encrypted 7-state local print attempt ledger (Doc 28 §6 & §8, DEV-002, DEV-004).
 * Completely separate from financial voucher Idempotency-Key posting; requires explicit operator
 * reconciliation of OUTCOME_UNKNOWN attempts before permitting an audited DUPLICATE / REPRINT.
 */
public final class PrintAttemptLedger {

    private static final byte[] MAGIC = {'M', 'C', 'P', 'L'};
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final Path encryptedLedgerPath;
    private final SecretKey secretKey;
    private final byte[] associatedData;
    private final Clock clock;
    private final DesktopJcsCanonicalizer canonicalizer;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<UUID, PrintAttemptRecord> records = new LinkedHashMap<>();

    public PrintAttemptLedger(Path encryptedLedgerPath, byte[] keyBytes32) {
        this(encryptedLedgerPath, keyBytes32, Clock.systemUTC());
    }

    public PrintAttemptLedger(Path encryptedLedgerPath, byte[] keyBytes32, Clock clock) {
        this.encryptedLedgerPath = Objects.requireNonNull(encryptedLedgerPath, "encryptedLedgerPath")
            .toAbsolutePath().normalize();
        Objects.requireNonNull(keyBytes32, "keyBytes32 must not be null");
        if (keyBytes32.length != 32) {
            throw new IllegalArgumentException("PrintAttemptLedger requires a 32-byte AES-256-GCM key.");
        }
        this.secretKey = new SecretKeySpec(Arrays.copyOf(keyBytes32, 32), "AES");
        String hostUser = System.getProperty("user.name", "unknown");
        this.associatedData = ("MC-PRINT-LEDGER-V1|" + hostUser + "|" + this.encryptedLedgerPath)
            .getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.canonicalizer = new DesktopJcsCanonicalizer();
        loadAndRecoverInterruptedAttempts();
    }

    public synchronized PrintAttemptRecord prepareAttempt(
        DeviceAuthorityContext authority,
        UUID documentId,
        long documentVersion,
        boolean containsSelectedPrivateFields,
        byte[] immutablePayloadBytes,
        DeviceProfile profile,
        String destination
    ) {
        Objects.requireNonNull(authority, "authority must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(immutablePayloadBytes, "immutablePayloadBytes must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        profile.assertAdmitted();
        DeviceProfile.validateSafeLocalDestination(destination);
        authority.assertCanOutputDocument(authority.authorizedCompanyId(), containsSelectedPrivateFields);
        assertNoUnreconciledUnknownAttempt(documentId);

        Instant now = clock.instant();
        PrintAttemptRecord record = new PrintAttemptRecord(
            UUID.randomUUID(),
            authority.authorizedCompanyId(),
            authority.actorPrincipalId(),
            documentId,
            documentVersion,
            containsSelectedPrivateFields,
            canonicalizer.sha256Hex(immutablePayloadBytes),
            profile.profileId(),
            profile.profileVersion(),
            destination,
            PrintAttemptState.PREPARED,
            CompletionEvidenceSource.NONE,
            false,
            null,
            null,
            false,
            null,
            "Prepared immutable output payload.",
            now,
            now,
            now.plus(DEFAULT_RETENTION)
        );
        records.put(record.attemptId(), record);
        persistEncrypted();
        return record;
    }

    public synchronized PrintAttemptRecord beginSubmitting(UUID attemptId, DeviceAuthorityContext currentAuthority) {
        Objects.requireNonNull(currentAuthority, "currentAuthority must not be null");
        PrintAttemptRecord existing = requireAttempt(attemptId);
        if (existing.state() == PrintAttemptState.CANCELLED_BEFORE_SUBMISSION) {
            throw new DeviceSecurityException(
                "MC-PRINT-CANCELLED-TERMINAL",
                "Cancelled attempt " + attemptId + " cannot be dispatched."
            );
        }
        if (existing.state() != PrintAttemptState.PREPARED) {
            throw new DeviceSecurityException(
                "MC-PRINT-INVALID-STATE",
                "Only PREPARED attempts may enter SUBMITTING (current: " + existing.state() + ")."
            );
        }
        currentAuthority.assertCanOutputDocument(existing.companyId(), existing.containsSelectedPrivateFields());
        assertNoUnreconciledUnknownAttempt(existing.documentId());

        PrintAttemptRecord updated = withState(
            existing,
            PrintAttemptState.SUBMITTING,
            CompletionEvidenceSource.NONE,
            existing.unknownReconciled(),
            existing.reconciliationNote(),
            "Transfer to local device/spooler starting."
        );
        records.put(attemptId, updated);
        persistEncrypted();
        return updated;
    }

    public synchronized PrintAttemptRecord cancelBeforeSubmission(
        UUID attemptId,
        DeviceAuthorityContext currentAuthority,
        String reason
    ) {
        Objects.requireNonNull(currentAuthority, "currentAuthority must not be null");
        PrintAttemptRecord existing = requireAttempt(attemptId);
        if (existing.state() != PrintAttemptState.PREPARED) {
            throw new DeviceSecurityException(
                "MC-PRINT-CANCEL-TOO-LATE",
                "Cannot cancel before submission once state is " + existing.state() + "."
            );
        }
        if (!currentAuthority.sessionActive()
            || !existing.companyId().equals(currentAuthority.authorizedCompanyId())) {
            throw new DeviceSecurityException(
                "MC-PRINT-CANCEL-DENIED",
                "Active matching company authority required to cancel prepared output."
            );
        }
        String note = reason == null || reason.isBlank() ? "Cancelled by operator before transfer." : reason.trim();
        PrintAttemptRecord updated = withState(
            existing,
            PrintAttemptState.CANCELLED_BEFORE_SUBMISSION,
            CompletionEvidenceSource.NONE,
            false,
            null,
            note
        );
        records.put(attemptId, updated);
        persistEncrypted();
        return updated;
    }

    public synchronized PrintAttemptRecord markFailedBeforeSubmission(UUID attemptId, String diagnosticReason) {
        PrintAttemptRecord existing = requireAttempt(attemptId);
        if (existing.state() != PrintAttemptState.PREPARED && existing.state() != PrintAttemptState.SUBMITTING) {
            throw new DeviceSecurityException(
                "MC-PRINT-INVALID-STATE",
                "Cannot mark FAILED_BEFORE_SUBMISSION from state " + existing.state() + "."
            );
        }
        PrintAttemptRecord updated = withState(
            existing,
            PrintAttemptState.FAILED_BEFORE_SUBMISSION,
            CompletionEvidenceSource.NONE,
            false,
            null,
            diagnosticReason == null ? "Failed before any bytes were transferred." : diagnosticReason
        );
        records.put(attemptId, updated);
        persistEncrypted();
        return updated;
    }

    public synchronized PrintAttemptRecord markSubmitted(
        UUID attemptId,
        CompletionEvidenceSource transportEvidence,
        String diagnosticNote
    ) {
        Objects.requireNonNull(transportEvidence, "transportEvidence must not be null");
        PrintAttemptRecord existing = requireAttempt(attemptId);
        if (existing.state() != PrintAttemptState.SUBMITTING) {
            throw new DeviceSecurityException(
                "MC-PRINT-INVALID-STATE",
                "Only SUBMITTING attempts may transition to SUBMITTED (current: " + existing.state() + ")."
            );
        }
        PrintAttemptRecord updated = withState(
            existing,
            PrintAttemptState.SUBMITTED,
            transportEvidence,
            false,
            null,
            diagnosticNote == null
                ? "Spooler or transport accepted bytes; physical paper completion not yet proven."
                : diagnosticNote
        );
        records.put(attemptId, updated);
        persistEncrypted();
        return updated;
    }

    public synchronized PrintAttemptRecord markConfirmedComplete(
        UUID attemptId,
        CompletionEvidenceSource qualifiedEvidence,
        String diagnosticNote
    ) {
        Objects.requireNonNull(qualifiedEvidence, "qualifiedEvidence must not be null");
        if (qualifiedEvidence != CompletionEvidenceSource.PRINTER_HARDWARE_CONFIRMED
            && qualifiedEvidence != CompletionEvidenceSource.OPERATOR_RECONCILED_PRINTED) {
            throw new DeviceSecurityException(
                "MC-PRINT-UNQUALIFIED-COMPLETION",
                "ND-S06 / DEV-002 violation: spooler/transport byte transfer (" + qualifiedEvidence
                    + ") cannot be recorded as CONFIRMED_COMPLETE physical output."
            );
        }
        PrintAttemptRecord existing = requireAttempt(attemptId);
        if (existing.state() != PrintAttemptState.SUBMITTING && existing.state() != PrintAttemptState.SUBMITTED) {
            throw new DeviceSecurityException(
                "MC-PRINT-INVALID-STATE",
                "Cannot confirm completion from state " + existing.state() + "."
            );
        }
        PrintAttemptRecord updated = withState(
            existing,
            PrintAttemptState.CONFIRMED_COMPLETE,
            qualifiedEvidence,
            existing.unknownReconciled(),
            existing.reconciliationNote(),
            diagnosticNote == null ? "Physical output completion verified." : diagnosticNote
        );
        records.put(attemptId, updated);
        persistEncrypted();
        return updated;
    }

    public synchronized PrintAttemptRecord markOutcomeUnknown(UUID attemptId, String interruptionReason) {
        PrintAttemptRecord existing = requireAttempt(attemptId);
        if (existing.state() != PrintAttemptState.SUBMITTING && existing.state() != PrintAttemptState.SUBMITTED) {
            throw new DeviceSecurityException(
                "MC-PRINT-INVALID-STATE",
                "Cannot mark OUTCOME_UNKNOWN from state " + existing.state() + "."
            );
        }
        PrintAttemptRecord updated = withState(
            existing,
            PrintAttemptState.OUTCOME_UNKNOWN,
            existing.evidenceSource(),
            false,
            null,
            interruptionReason == null
                ? "Connection or acknowledgement lost during transfer; reconcile queue before reprinting."
                : interruptionReason
        );
        records.put(attemptId, updated);
        persistEncrypted();
        return updated;
    }

    /**
     * Records deliberate operator reconciliation of an OUTCOME_UNKNOWN attempt after inspecting
     * the physical printer / OS spooler history (Doc 28 §6, AT09-DEV-002-B/C).
     */
    public synchronized PrintAttemptRecord reconcileUnknownAttempt(
        UUID attemptId,
        DeviceAuthorityContext currentAuthority,
        boolean physicalOutputObserved,
        String operatorNote
    ) {
        Objects.requireNonNull(currentAuthority, "currentAuthority must not be null");
        if (operatorNote == null || operatorNote.isBlank()) {
            throw new DeviceSecurityException(
                "MC-PRINT-RECONCILE-NOTE-REQUIRED",
                "Operator reconciliation of OUTCOME_UNKNOWN requires an explicit audit note."
            );
        }
        PrintAttemptRecord existing = requireAttempt(attemptId);
        if (existing.state() != PrintAttemptState.OUTCOME_UNKNOWN) {
            throw new DeviceSecurityException(
                "MC-PRINT-NOT-UNKNOWN",
                "Attempt " + attemptId + " is in state " + existing.state() + ", not OUTCOME_UNKNOWN."
            );
        }
        currentAuthority.assertCanOutputDocument(existing.companyId(), existing.containsSelectedPrivateFields());

        CompletionEvidenceSource evidence = physicalOutputObserved
            ? CompletionEvidenceSource.OPERATOR_RECONCILED_PRINTED
            : CompletionEvidenceSource.OPERATOR_RECONCILED_NOT_PRINTED;
        PrintAttemptRecord updated = withState(
            existing,
            PrintAttemptState.OUTCOME_UNKNOWN,
            evidence,
            true,
            operatorNote.trim(),
            "Reconciled by operator " + currentAuthority.actorPrincipalId() + ": " + operatorNote.trim()
        );
        records.put(attemptId, updated);
        persistEncrypted();
        return updated;
    }

    /**
     * Creates a new audited DUPLICATE / REPRINT attempt linked to the immutable document/version
     * without ever re-executing voucher creation, approval, or posting (DEV-002).
     */
    public synchronized PrintAttemptRecord createAuditedReprint(
        UUID originalAttemptId,
        DeviceAuthorityContext currentAuthority,
        String reprintReason,
        byte[] duplicateBannerPayloadBytes
    ) {
        Objects.requireNonNull(originalAttemptId, "originalAttemptId must not be null");
        Objects.requireNonNull(currentAuthority, "currentAuthority must not be null");
        Objects.requireNonNull(duplicateBannerPayloadBytes, "duplicateBannerPayloadBytes must not be null");
        if (reprintReason == null || reprintReason.isBlank()) {
            throw new DeviceSecurityException(
                "MC-PRINT-REPRINT-REASON-REQUIRED",
                "Audited reprint requires a non-blank operator reason."
            );
        }

        PrintAttemptRecord original = requireAttempt(originalAttemptId);
        currentAuthority.assertCanOutputDocument(original.companyId(), original.containsSelectedPrivateFields());
        assertNoUnreconciledUnknownAttempt(original.documentId());

        if (original.state() != PrintAttemptState.OUTCOME_UNKNOWN
            && original.state() != PrintAttemptState.SUBMITTED
            && original.state() != PrintAttemptState.CONFIRMED_COMPLETE
            && original.state() != PrintAttemptState.FAILED_BEFORE_SUBMISSION) {
            throw new DeviceSecurityException(
                "MC-PRINT-REPRINT-INVALID-ORIGIN",
                "Cannot create audited reprint from attempt in state " + original.state() + "."
            );
        }

        Instant now = clock.instant();
        PrintAttemptRecord reprintAttempt = new PrintAttemptRecord(
            UUID.randomUUID(),
            original.companyId(),
            currentAuthority.actorPrincipalId(),
            original.documentId(),
            original.documentVersion(),
            original.containsSelectedPrivateFields(),
            canonicalizer.sha256Hex(duplicateBannerPayloadBytes),
            original.profileId(),
            original.profileVersion(),
            original.destination(),
            PrintAttemptState.PREPARED,
            CompletionEvidenceSource.NONE,
            true,
            original.attemptId(),
            reprintReason.trim(),
            false,
            null,
            "Audited DUPLICATE / REPRINT prepared for document v" + original.documentVersion(),
            now,
            now,
            now.plus(DEFAULT_RETENTION)
        );
        records.put(reprintAttempt.attemptId(), reprintAttempt);
        persistEncrypted();
        return reprintAttempt;
    }

    public synchronized Optional<PrintAttemptRecord> findAttempt(UUID attemptId) {
        return Optional.ofNullable(records.get(attemptId));
    }

    public synchronized List<PrintAttemptRecord> listAttemptsForCompany(UUID companyId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        List<PrintAttemptRecord> result = new ArrayList<>();
        for (PrintAttemptRecord rec : records.values()) {
            if (companyId.equals(rec.companyId())) {
                result.add(rec);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Purges expired or terminal non-unknown spool records while retaining unresolved OUTCOME_UNKNOWN
     * records so operator recovery never loses visibility of uncertain physical output (Doc 28 §8).
     */
    public synchronized int cleanupCompletedAndExpired() {
        Instant now = clock.instant();
        int before = records.size();
        records.entrySet().removeIf(entry -> {
            PrintAttemptRecord rec = entry.getValue();
            if (rec.state() == PrintAttemptState.OUTCOME_UNKNOWN && !rec.unknownReconciled()) {
                return false;
            }
            if (!now.isBefore(rec.expiresAt())) {
                return true;
            }
            return rec.state() == PrintAttemptState.CANCELLED_BEFORE_SUBMISSION
                || rec.state() == PrintAttemptState.FAILED_BEFORE_SUBMISSION;
        });
        int removed = before - records.size();
        if (removed > 0) {
            persistEncrypted();
        }
        return removed;
    }

    private void assertNoUnreconciledUnknownAttempt(UUID documentId) {
        for (PrintAttemptRecord rec : records.values()) {
            if (documentId.equals(rec.documentId())
                && rec.state() == PrintAttemptState.OUTCOME_UNKNOWN
                && !rec.unknownReconciled()) {
                throw new DeviceSecurityException(
                    "MC-PRINT-RECONCILE-REQUIRED",
                    "Document " + documentId + " has unreconciled OUTCOME_UNKNOWN attempt "
                        + rec.attemptId() + "; reconcile physical status before creating another attempt."
                );
            }
        }
    }

    private PrintAttemptRecord requireAttempt(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        PrintAttemptRecord existing = records.get(attemptId);
        if (existing == null) {
            throw new DeviceSecurityException("MC-PRINT-ATTEMPT-NOT-FOUND", "Unknown print attempt: " + attemptId);
        }
        return existing;
    }

    private PrintAttemptRecord withState(
        PrintAttemptRecord src,
        PrintAttemptState nextState,
        CompletionEvidenceSource evidenceSource,
        boolean unknownReconciled,
        String reconciliationNote,
        String diagnosticMessage
    ) {
        return new PrintAttemptRecord(
            src.attemptId(),
            src.companyId(),
            src.actorPrincipalId(),
            src.documentId(),
            src.documentVersion(),
            src.containsSelectedPrivateFields(),
            src.payloadSha256Hex(),
            src.profileId(),
            src.profileVersion(),
            src.destination(),
            nextState,
            evidenceSource,
            src.reprint(),
            src.reprintOfAttemptId(),
            src.reprintReason(),
            unknownReconciled,
            reconciliationNote,
            diagnosticMessage,
            src.createdAt(),
            clock.instant(),
            src.expiresAt()
        );
    }

    private void loadAndRecoverInterruptedAttempts() {
        if (!Files.exists(encryptedLedgerPath)) {
            return;
        }
        try {
            byte[] raw = Files.readAllBytes(encryptedLedgerPath);
            if (raw.length <= MAGIC.length + GCM_IV_BYTES + 16) {
                throw new DeviceSecurityException("MC-PRINT-LEDGER-CORRUPT", "Print ledger file is truncated.");
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (raw[i] != MAGIC[i]) {
                    throw new DeviceSecurityException("MC-PRINT-LEDGER-CORRUPT", "Print ledger header mismatch.");
                }
            }
            byte[] iv = Arrays.copyOfRange(raw, MAGIC.length, MAGIC.length + GCM_IV_BYTES);
            byte[] cipherBytes = Arrays.copyOfRange(raw, MAGIC.length + GCM_IV_BYTES, raw.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] plaintext = cipher.doFinal(cipherBytes);
            try {
                String json = new String(plaintext, StandardCharsets.UTF_8);
                List<PrintAttemptRecord> loaded = canonicalizer.wireMapper().readValue(
                    json,
                    new TypeReference<>() {
                    }
                );
                boolean mutatedDuringRecovery = false;
                for (PrintAttemptRecord rec : loaded) {
                    if (rec.state() == PrintAttemptState.SUBMITTING) {
                        PrintAttemptRecord recovered = withState(
                            rec,
                            PrintAttemptState.OUTCOME_UNKNOWN,
                            rec.evidenceSource(),
                            false,
                            null,
                            "Recovered interrupted SUBMITTING attempt after restart as OUTCOME_UNKNOWN."
                        );
                        records.put(recovered.attemptId(), recovered);
                        mutatedDuringRecovery = true;
                    } else {
                        records.put(rec.attemptId(), rec);
                    }
                }
                if (mutatedDuringRecovery) {
                    persistEncrypted();
                }
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (DeviceSecurityException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DeviceSecurityException(
                "MC-PRINT-LEDGER-AUTH-FAILED",
                "AES-GCM integrity check failed on local print attempt ledger.",
                ex
            );
        }
    }

    private void persistEncrypted() {
        try {
            Path parent = encryptedLedgerPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<PrintAttemptRecord> snapshot = new ArrayList<>(records.values());
            byte[] plaintext = canonicalizer.writeWireJson(snapshot).getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] ciphertext = cipher.doFinal(plaintext);
            Arrays.fill(plaintext, (byte) 0);

            ByteBuffer buf = ByteBuffer.allocate(MAGIC.length + GCM_IV_BYTES + ciphertext.length);
            buf.put(MAGIC);
            buf.put(iv);
            buf.put(ciphertext);

            Path tmp = encryptedLedgerPath.resolveSibling(encryptedLedgerPath.getFileName() + ".tmp");
            Files.write(tmp, buf.array());
            restrictOwnerOnly(tmp);
            Files.move(tmp, encryptedLedgerPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            restrictOwnerOnly(encryptedLedgerPath);
        } catch (IOException | GeneralSecurityException ex) {
            throw new DeviceSecurityException("MC-PRINT-LEDGER-IO-ERROR", "Failed persisting encrypted print ledger.", ex);
        }
    }

    private static void restrictOwnerOnly(Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view != null) {
            Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(path, perms);
        }
    }
}
