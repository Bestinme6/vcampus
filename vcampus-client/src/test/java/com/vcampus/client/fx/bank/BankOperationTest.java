package com.vcampus.client.fx.bank;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankOperationTest {
    @Test void unknownResultRetainsExactRequestAndBlocksDuplicateSubmission() {
        var operation = new BankOperation(BankOperation.Kind.TRANSFER, "teacher", "李老师", "12.30");
        String id = operation.id();
        assertTrue(operation.begin());
        assertFalse(operation.begin());
        operation.unknown();
        assertFalse(operation.terminal());
        assertTrue(operation.begin());
        assertEquals(id, operation.id());
        assertEquals("teacher", operation.username());
        assertEquals("12.30", operation.amount().toPlainString());
        operation.complete();
        assertFalse(operation.begin());
    }
    @Test void invalidAmountsCannotCreateADraft() {
        for (String amount : new String[]{"0", "-1", "1e2", "1.234"})
            assertThrows(IllegalArgumentException.class,
                    () -> new BankOperation(BankOperation.Kind.TOPUP,"s","同学",amount));
    }
}
