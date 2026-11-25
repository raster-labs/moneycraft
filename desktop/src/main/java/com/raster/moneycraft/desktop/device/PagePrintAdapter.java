package com.raster.moneycraft.desktop.device;

import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.JobName;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;

/**
 * Ordinary OS-driver page printing adapter (Doc 28 §5.1, ND-S04..S06, DEV-001 Path A).
 * Queries {@link PrintService} {@link DocFlavor} capability before submitting server-rendered
 * PDF bytes via {@link PrinterJob} / {@link DocPrintJob}, failing explicitly with actionable
 * guidance if the OS service rejects the PDF flavour, and never conflating data transfer
 * completion with physical paper completion.
 */
public final class PagePrintAdapter {

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final DocFlavor REQUIRED_PDF_FLAVOR = DocFlavor.BYTE_ARRAY.PDF;

    private final PrintAttemptLedger attemptLedger;
    private final OsPagePrintBackend backend;

    public PagePrintAdapter(PrintAttemptLedger attemptLedger) {
        this(attemptLedger, new JavaxPrintOsBackend());
    }

    public PagePrintAdapter(PrintAttemptLedger attemptLedger, OsPagePrintBackend backend) {
        this.attemptLedger = Objects.requireNonNull(attemptLedger, "attemptLedger must not be null");
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
    }

    /**
     * Queries OS print service capability and submits authorised server-rendered PDF bytes.
     */
    public PrintAttemptRecord printServerPdf(
        DeviceAuthorityContext authority,
        UUID documentId,
        long documentVersion,
        boolean containsSelectedPrivateFields,
        byte[] serverPdfBytes,
        DeviceProfile profile,
        String serviceName
    ) {
        Objects.requireNonNull(authority, "authority must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(serviceName, "serviceName must not be null");

        profile.assertAdmitted();
        if (profile.commandLanguage() != DeviceProfile.CommandLanguage.OS_PAGE_PDF) {
            throw new DeviceSecurityException(
                "MC-PAGE-PRINT-PROFILE-MISMATCH",
                "PagePrintAdapter requires an OS_PAGE_PDF DeviceProfile (got " + profile.commandLanguage() + ")."
            );
        }
        assertServerPdfBytesOnly(serverPdfBytes);

        PrintAttemptRecord prepared = attemptLedger.prepareAttempt(
            authority,
            documentId,
            documentVersion,
            containsSelectedPrivateFields,
            serverPdfBytes,
            profile,
            serviceName
        );

        OsServiceCapability capability = backend.inspectServiceCapability(serviceName, REQUIRED_PDF_FLAVOR);
        if (!capability.serviceFound()) {
            return attemptLedger.markFailedBeforeSubmission(
                prepared.attemptId(),
                "OS print service '" + serviceName + "' was not found; no bytes were transferred. "
                    + "Select an installed qualified OS printer or export the PDF."
            );
        }
        if (!capability.flavorSupported()) {
            return attemptLedger.markFailedBeforeSubmission(
                prepared.attemptId(),
                "OS print service '" + serviceName + "' rejected DocFlavor '" + REQUIRED_PDF_FLAVOR.getMimeType()
                    + "' (supported: " + capability.supportedMimeTypes() + "); zero bytes transferred. "
                    + "Use Export PDF with the platform PDF viewer or select a PDF-capable OS print driver."
            );
        }

        attemptLedger.beginSubmitting(prepared.attemptId(), authority);
        try {
            OsSubmissionOutcome outcome = backend.submitPdfJob(
                serviceName,
                "MoneyCraft-" + documentId + "-v" + documentVersion,
                serverPdfBytes
            );
            if (outcome.printerHardwareConfirmed()
                && profile.completionEvidenceCapability()
                    == DeviceProfile.CompletionEvidenceCapability.PRINTER_HARDWARE_CONFIRMED) {
                return attemptLedger.markConfirmedComplete(
                    prepared.attemptId(),
                    CompletionEvidenceSource.PRINTER_HARDWARE_CONFIRMED,
                    outcome.diagnostic()
                );
            }
            if (outcome.dataTransferredToSpooler()) {
                return attemptLedger.markSubmitted(
                    prepared.attemptId(),
                    CompletionEvidenceSource.SPOOLER_DATA_TRANSFERRED,
                    outcome.diagnostic()
                );
            }
            return attemptLedger.markOutcomeUnknown(prepared.attemptId(), outcome.diagnostic());
        } catch (BeforeTransferFailureException ex) {
            return attemptLedger.markFailedBeforeSubmission(prepared.attemptId(), ex.getMessage());
        } catch (RuntimeException ex) {
            return attemptLedger.markOutcomeUnknown(
                prepared.attemptId(),
                "Print submission interrupted during transfer: " + ex.getMessage()
            );
        }
    }

    private static void assertServerPdfBytesOnly(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length < PDF_SIGNATURE.length) {
            throw new DeviceSecurityException(
                "MC-PAGE-PRINT-INVALID-PDF",
                "PagePrintAdapter requires non-empty server-rendered %PDF- bytes."
            );
        }
        for (int i = 0; i < PDF_SIGNATURE.length; i++) {
            if (pdfBytes[i] != PDF_SIGNATURE[i]) {
                throw new DeviceSecurityException(
                    "MC-PAGE-PRINT-NON-PDF-REJECTED",
                    "Rejected non-PDF payload (arbitrary HTML, CSS, scripts, or URLs are forbidden)."
                );
            }
        }
    }

    public record OsServiceCapability(
        boolean serviceFound,
        boolean flavorSupported,
        List<String> supportedMimeTypes
    ) {
        public OsServiceCapability {
            supportedMimeTypes = supportedMimeTypes == null ? List.of() : List.copyOf(supportedMimeTypes);
        }
    }

    public record OsSubmissionOutcome(
        boolean dataTransferredToSpooler,
        boolean printerHardwareConfirmed,
        String diagnostic
    ) {
    }

    public static final class BeforeTransferFailureException extends RuntimeException {
        public BeforeTransferFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public interface OsPagePrintBackend {
        OsServiceCapability inspectServiceCapability(String serviceName, DocFlavor requiredFlavor);

        OsSubmissionOutcome submitPdfJob(String serviceName, String jobName, byte[] pdfBytes);
    }

    /**
     * Production {@link PrinterJob} and {@link javax.print.PrintService} lookup implementation.
     */
    public static final class JavaxPrintOsBackend implements OsPagePrintBackend {

        @Override
        public OsServiceCapability inspectServiceCapability(String serviceName, DocFlavor requiredFlavor) {
            PrintService matched = findService(serviceName);
            if (matched == null) {
                return new OsServiceCapability(false, false, List.of());
            }
            List<String> mimes = new ArrayList<>();
            for (DocFlavor flavor : matched.getSupportedDocFlavors()) {
                mimes.add(flavor.getMimeType());
            }
            boolean supported = matched.isDocFlavorSupported(requiredFlavor);
            return new OsServiceCapability(true, supported, mimes);
        }

        @Override
        public OsSubmissionOutcome submitPdfJob(String serviceName, String jobName, byte[] pdfBytes) {
            PrintService service = findService(serviceName);
            if (service == null) {
                throw new BeforeTransferFailureException("PrintService disappeared before submission.", null);
            }
            PrinterJob printerJob = PrinterJob.getPrinterJob();
            try {
                printerJob.setPrintService(service);
                printerJob.setJobName(jobName);
            } catch (PrinterException ex) {
                throw new BeforeTransferFailureException(
                    "PrinterJob rejected print service '" + serviceName + "' before transfer.",
                    ex
                );
            }

            DocPrintJob docJob = printerJob.getPrintService().createPrintJob();
            AtomicBoolean dataTransferred = new AtomicBoolean(false);
            AtomicBoolean jobCompleted = new AtomicBoolean(false);
            docJob.addPrintJobListener(new PrintJobAdapter() {
                @Override
                public void printDataTransferCompleted(PrintJobEvent pje) {
                    dataTransferred.set(true);
                }

                @Override
                public void printJobCompleted(PrintJobEvent pje) {
                    jobCompleted.set(true);
                }
            });

            Doc doc = new SimpleDoc(pdfBytes, REQUIRED_PDF_FLAVOR, null);
            PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
            attrs.add(new JobName(jobName, null));
            try {
                docJob.print(doc, attrs);
                return new OsSubmissionOutcome(
                    true,
                    jobCompleted.get(),
                    dataTransferred.get()
                        ? "PrintJobEvent.DATA_TRANSFER_COMPLETE received from OS spooler."
                        : "OS spooler accepted print call without synchronous transfer event."
                );
            } catch (PrintException ex) {
                throw new IllegalStateException("DocPrintJob failed: " + ex.getMessage(), ex);
            }
        }

        private static PrintService findService(String serviceName) {
            PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
            if (services == null) {
                return null;
            }
            for (PrintService svc : services) {
                if (serviceName.equalsIgnoreCase(svc.getName())) {
                    return svc;
                }
            }
            return null;
        }
    }
}
