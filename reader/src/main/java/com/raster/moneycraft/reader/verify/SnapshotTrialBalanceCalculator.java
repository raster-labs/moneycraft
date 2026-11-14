package com.raster.moneycraft.reader.verify;

import com.raster.moneycraft.reader.model.RawGroup;
import com.raster.moneycraft.reader.model.RawInventoryLine;
import com.raster.moneycraft.reader.model.RawLedger;
import com.raster.moneycraft.reader.model.RawStockItem;
import com.raster.moneycraft.reader.model.RawVoucher;
import com.raster.moneycraft.reader.model.RawVoucherLine;
import com.raster.moneycraft.reader.model.RawVoucherType;
import com.raster.moneycraft.reader.model.ReconciliationSummary;
import com.raster.moneycraft.reader.model.TrialBalanceLine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes exact fixed-point double-entry turnover, ledger & top-group Trial Balance,
 * perpetual Avg. Cost / FIFO closing stock, Gross Profit, and Net Profit using BigDecimal exclusively.
 */
public final class SnapshotTrialBalanceCalculator {

    private static final BigDecimal ZERO_2DP = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    public ReconciliationSummary calculate(
            List<RawGroup> groups,
            List<RawLedger> ledgers,
            List<RawVoucherType> voucherTypes,
            List<RawStockItem> stockItems,
            List<RawVoucher> vouchers,
            boolean shadowCommitVerified) {
        Map<String, RawGroup> groupsByName = new HashMap<>();
        for (RawGroup g : groups) {
            groupsByName.put(g.name(), g);
        }

        Map<String, LedgerAccumulator> accumulators = new LinkedHashMap<>();
        for (RawLedger ledger : ledgers) {
            accumulators.put(
                    ledger.name(),
                    new LedgerAccumulator(ledger.name(), ledger.parentGroupName(), scale2(ledger.openingBalanceSigned())));
        }

        int postedVoucherCount = 0;
        int deletedVoucherCount = 0;
        int cancelledVoucherCount = 0;
        int optionalVoucherCount = 0;
        boolean allVouchersBalanced = true;
        BigDecimal grossDebitTurnover = ZERO_2DP;
        BigDecimal grossCreditTurnover = ZERO_2DP;

        for (RawVoucher voucher : vouchers) {
            if (voucher.deleted()) {
                deletedVoucherCount++;
                continue;
            }
            if (voucher.cancelled()) {
                cancelledVoucherCount++;
                continue;
            }
            if (voucher.optional()) {
                optionalVoucherCount++;
                continue;
            }
            if (voucher.ledgerLines().isEmpty()) {
                continue;
            }

            postedVoucherCount++;
            BigDecimal voucherDr = ZERO_2DP;
            BigDecimal voucherCr = ZERO_2DP;

            for (RawVoucherLine line : voucher.ledgerLines()) {
                BigDecimal signed = scale2(line.amountSigned());
                LedgerAccumulator acc = accumulators.computeIfAbsent(
                        line.ledgerName(),
                        lname -> new LedgerAccumulator(lname, "Suspense A/c", ZERO_2DP));
                if (signed.signum() < 0) {
                    BigDecimal dr = signed.negate();
                    voucherDr = voucherDr.add(dr);
                    grossDebitTurnover = grossDebitTurnover.add(dr);
                    acc.debitTurnover = acc.debitTurnover.add(dr);
                } else if (signed.signum() > 0) {
                    voucherCr = voucherCr.add(signed);
                    grossCreditTurnover = grossCreditTurnover.add(signed);
                    acc.creditTurnover = acc.creditTurnover.add(signed);
                }
            }

            if (voucherDr.compareTo(voucherCr) != 0 || voucherDr.signum() <= 0) {
                allVouchersBalanced = false;
            }
        }

        List<TrialBalanceLine> tbLines = new ArrayList<>();
        BigDecimal netTbDebit = ZERO_2DP;
        BigDecimal netTbCredit = ZERO_2DP;
        Map<String, BigDecimal> topGroupSignedTotals = new HashMap<>();

        BigDecimal directTradingNetSigned = ZERO_2DP;
        BigDecimal indirectRevenueNetSigned = ZERO_2DP;
        BigDecimal openingStockTotal = ZERO_2DP;

        List<LedgerAccumulator> sortedAccumulators = new ArrayList<>(accumulators.values());
        sortedAccumulators.sort(Comparator.comparing(a -> a.ledgerName));

        for (LedgerAccumulator acc : sortedAccumulators) {
            BigDecimal closingSigned = acc.openingSigned
                    .subtract(acc.debitTurnover)
                    .add(acc.creditTurnover);
            BigDecimal closingDr = closingSigned.signum() < 0 ? closingSigned.negate() : ZERO_2DP;
            BigDecimal closingCr = closingSigned.signum() > 0 ? closingSigned : ZERO_2DP;

            netTbDebit = netTbDebit.add(closingDr);
            netTbCredit = netTbCredit.add(closingCr);

            tbLines.add(new TrialBalanceLine(
                    acc.ledgerName,
                    acc.parentGroupName,
                    acc.openingSigned,
                    acc.debitTurnover,
                    acc.creditTurnover,
                    closingDr,
                    closingCr,
                    closingSigned));

            RawGroup topGroup = resolveTopGroup(acc.parentGroupName, groupsByName);
            String topGroupName = topGroup != null ? topGroup.name() : acc.parentGroupName;
            topGroupSignedTotals.merge(topGroupName, closingSigned, BigDecimal::add);

            if ("Stock-in-Hand".equals(acc.parentGroupName) && acc.openingSigned.signum() < 0) {
                openingStockTotal = openingStockTotal.add(acc.openingSigned.negate());
            }

            if (topGroup != null && topGroup.revenue()) {
                if (topGroup.affectsGrossProfit()) {
                    directTradingNetSigned = directTradingNetSigned.add(closingSigned);
                } else {
                    indirectRevenueNetSigned = indirectRevenueNetSigned.add(closingSigned);
                }
            }
        }

        BigDecimal topGroupDr = ZERO_2DP;
        BigDecimal topGroupCr = ZERO_2DP;
        for (BigDecimal grpSigned : topGroupSignedTotals.values()) {
            if (grpSigned.signum() < 0) {
                topGroupDr = topGroupDr.add(grpSigned.negate());
            } else if (grpSigned.signum() > 0) {
                topGroupCr = topGroupCr.add(grpSigned);
            }
        }

        for (RawStockItem item : stockItems) {
            if (item.openingValueSigned().signum() != 0) {
                openingStockTotal = openingStockTotal.add(item.openingValueSigned().abs());
            }
        }

        BigDecimal closingStockValue = computeClosingStockValue(stockItems, vouchers);
        BigDecimal grossProfit = scale2(directTradingNetSigned.add(closingStockValue).subtract(openingStockTotal));
        BigDecimal netProfit = scale2(grossProfit.add(indirectRevenueNetSigned));

        boolean tbReconciled = allVouchersBalanced
                && grossDebitTurnover.compareTo(grossCreditTurnover) == 0
                && netTbDebit.compareTo(netTbCredit) == 0
                && topGroupDr.compareTo(topGroupCr) == 0;

        return new ReconciliationSummary(
                groups.size(),
                ledgers.size(),
                voucherTypes.size(),
                postedVoucherCount,
                deletedVoucherCount,
                cancelledVoucherCount,
                optionalVoucherCount,
                grossDebitTurnover,
                grossCreditTurnover,
                netTbDebit,
                netTbCredit,
                topGroupDr,
                topGroupCr,
                closingStockValue,
                grossProfit,
                netProfit,
                shadowCommitVerified,
                allVouchersBalanced,
                tbReconciled,
                tbLines);
    }

    private static RawGroup resolveTopGroup(String groupName, Map<String, RawGroup> groupsByName) {
        if (groupName == null || "Primary".equals(groupName)) {
            return null;
        }
        RawGroup current = groupsByName.get(groupName);
        int safety = 0;
        while (current != null && !"Primary".equals(current.parentName()) && safety < 32) {
            RawGroup parent = groupsByName.get(current.parentName());
            if (parent == null) {
                break;
            }
            current = parent;
            safety++;
        }
        return current;
    }

    private static BigDecimal computeClosingStockValue(List<RawStockItem> stockItems, List<RawVoucher> vouchers) {
        Map<String, RawStockItem> itemsByName = new HashMap<>();
        for (RawStockItem item : stockItems) {
            itemsByName.put(item.name(), item);
        }

        List<RawVoucher> activeVouchers = new ArrayList<>();
        for (RawVoucher v : vouchers) {
            if (!v.deleted() && !v.cancelled() && !v.optional() && !v.inventoryLines().isEmpty()) {
                activeVouchers.add(v);
            }
        }
        if (activeVouchers.isEmpty() && stockItems.isEmpty()) {
            return ZERO_2DP;
        }

        activeVouchers.sort(Comparator
                .comparing((RawVoucher v) -> v.date() != null ? v.date() : LocalDate.MIN)
                .thenComparingInt(RawVoucher::masterId));

        Map<String, ItemValuationState> states = new LinkedHashMap<>();
        for (RawStockItem item : stockItems) {
            states.put(item.name(), new ItemValuationState(item.costingMethod(), item.openingQty(), item.openingValueSigned().abs()));
        }

        for (RawVoucher v : activeVouchers) {
            for (RawInventoryLine inv : v.inventoryLines()) {
                RawStockItem meta = itemsByName.get(inv.stockItemName());
                String method = meta != null ? meta.costingMethod() : "Avg. Cost";
                ItemValuationState state = states.computeIfAbsent(
                        inv.stockItemName(),
                        k -> new ItemValuationState(method, BigDecimal.ZERO, BigDecimal.ZERO));

                BigDecimal qty = inv.actualQty().signum() > 0 ? inv.actualQty() : inv.billedQty();
                if (qty.signum() <= 0) {
                    continue;
                }
                if (inv.deemedPositive()) {
                    BigDecimal inwardValue = inv.amountSigned().abs();
                    state.recordInward(qty, inwardValue);
                } else {
                    state.recordOutward(qty);
                }
            }
        }

        BigDecimal totalClosing = ZERO_2DP;
        for (ItemValuationState state : states.values()) {
            totalClosing = totalClosing.add(state.closingValueRounded());
        }
        return totalClosing;
    }

    private static BigDecimal scale2(BigDecimal val) {
        if (val == null) {
            return ZERO_2DP;
        }
        return val.setScale(2, RoundingMode.HALF_UP);
    }

    private static final class LedgerAccumulator {
        private final String ledgerName;
        private final String parentGroupName;
        private final BigDecimal openingSigned;
        private BigDecimal debitTurnover = ZERO_2DP;
        private BigDecimal creditTurnover = ZERO_2DP;

        LedgerAccumulator(String ledgerName, String parentGroupName, BigDecimal openingSigned) {
            this.ledgerName = ledgerName;
            this.parentGroupName = parentGroupName;
            this.openingSigned = openingSigned;
        }
    }

    private static final class ItemValuationState {
        private final boolean fifo;
        private BigDecimal avgPoolQty = BigDecimal.ZERO;
        private BigDecimal avgPoolValue = BigDecimal.ZERO;
        private final Deque<FifoLot> fifoLots = new ArrayDeque<>();

        ItemValuationState(String costingMethod, BigDecimal openingQty, BigDecimal openingValue) {
            this.fifo = costingMethod != null && costingMethod.toUpperCase().contains("FIFO");
            if (openingQty.signum() > 0) {
                recordInward(openingQty, openingValue);
            }
        }

        void recordInward(BigDecimal qty, BigDecimal value) {
            if (fifo) {
                BigDecimal unitCost = value.divide(qty, MathContext.DECIMAL128);
                fifoLots.addLast(new FifoLot(qty, unitCost));
            } else {
                avgPoolQty = avgPoolQty.add(qty, MathContext.DECIMAL128);
                avgPoolValue = avgPoolValue.add(value, MathContext.DECIMAL128);
            }
        }

        void recordOutward(BigDecimal qty) {
            if (fifo) {
                BigDecimal remainingToIssue = qty;
                while (remainingToIssue.signum() > 0 && !fifoLots.isEmpty()) {
                    FifoLot head = fifoLots.peekFirst();
                    if (head.qtyRemaining.compareTo(remainingToIssue) <= 0) {
                        remainingToIssue = remainingToIssue.subtract(head.qtyRemaining, MathContext.DECIMAL128);
                        fifoLots.removeFirst();
                    } else {
                        head.qtyRemaining = head.qtyRemaining.subtract(remainingToIssue, MathContext.DECIMAL128);
                        remainingToIssue = BigDecimal.ZERO;
                    }
                }
            } else {
                if (avgPoolQty.compareTo(qty) <= 0) {
                    avgPoolQty = BigDecimal.ZERO;
                    avgPoolValue = BigDecimal.ZERO;
                } else {
                    BigDecimal avgCost = avgPoolValue.divide(avgPoolQty, MathContext.DECIMAL128);
                    avgPoolQty = avgPoolQty.subtract(qty, MathContext.DECIMAL128);
                    avgPoolValue = avgPoolQty.multiply(avgCost, MathContext.DECIMAL128);
                }
            }
        }

        BigDecimal closingValueRounded() {
            if (fifo) {
                BigDecimal sum = BigDecimal.ZERO;
                for (FifoLot lot : fifoLots) {
                    sum = sum.add(lot.qtyRemaining.multiply(lot.unitCost, MathContext.DECIMAL128), MathContext.DECIMAL128);
                }
                return sum.setScale(2, RoundingMode.HALF_UP);
            }
            if (avgPoolQty.signum() <= 0) {
                return ZERO_2DP;
            }
            return avgPoolValue.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static final class FifoLot {
        private BigDecimal qtyRemaining;
        private final BigDecimal unitCost;

        FifoLot(BigDecimal qtyRemaining, BigDecimal unitCost) {
            this.qtyRemaining = qtyRemaining;
            this.unitCost = unitCost;
        }
    }
}
