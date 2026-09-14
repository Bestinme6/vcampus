package com.vcampus.common.model;

import com.vcampus.common.protocol.Actions;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFeeVocabularyTest {
    @Test void definesStableActionsAndEnums() {
        assertEquals("library.fee.my", Actions.LIBRARY_FEE_MY);
        assertEquals("library.fee.get", Actions.LIBRARY_FEE_GET);
        assertEquals("library.fee.payment.preview", Actions.LIBRARY_FEE_PAYMENT_PREVIEW);
        assertEquals("library.fee.pay", Actions.LIBRARY_FEE_PAY);
        assertEquals("library.admin.fee.search", Actions.LIBRARY_ADMIN_FEE_SEARCH);
        assertEquals("library.admin.fee.preview", Actions.LIBRARY_ADMIN_FEE_PREVIEW);
        assertEquals("library.admin.fee.waive", Actions.LIBRARY_ADMIN_FEE_WAIVE);
        assertEquals("library.admin.fee.refund", Actions.LIBRARY_ADMIN_FEE_REFUND);
        assertEquals("library.admin.fee-policy.get", Actions.LIBRARY_ADMIN_FEE_POLICY_GET);
        assertEquals("library.admin.fee-policy.update", Actions.LIBRARY_ADMIN_FEE_POLICY_UPDATE);
        assertTrue(EnumSet.allOf(BankLedgerType.class).containsAll(
                Set.of(BankLedgerType.LIBRARY_FEE, BankLedgerType.LIBRARY_REFUND)));
        assertTrue(EnumSet.allOf(NotificationType.class).containsAll(Set.of(
                NotificationType.LIBRARY_FEE_CREATED, NotificationType.LIBRARY_FEE_PAID,
                NotificationType.LIBRARY_FEE_WAIVED, NotificationType.LIBRARY_FEE_REFUNDED)));
        assertEquals(NotificationTarget.LIBRARY_FEES, NotificationTarget.valueOf("LIBRARY_FEES"));
    }
}
