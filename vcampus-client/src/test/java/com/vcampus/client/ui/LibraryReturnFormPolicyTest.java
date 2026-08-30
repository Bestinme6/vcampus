package com.vcampus.client.ui;

import com.vcampus.common.model.LibraryReturnCondition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryReturnFormPolicyTest {
    @Test
    void damagedAndLostReturnsRequireReasonsButNormalReturnDoesNot() {
        assertFalse(LibraryReturnFormPolicy.requiresReason(LibraryReturnCondition.NORMAL));
        assertTrue(LibraryReturnFormPolicy.requiresReason(LibraryReturnCondition.DAMAGED));
        assertTrue(LibraryReturnFormPolicy.requiresReason(LibraryReturnCondition.LOST));
    }

    @Test
    void damagedReturnUsesReadableLabelValidationAndConfirmation() {
        assertEquals("破损", LibraryReturnFormPolicy.label(LibraryReturnCondition.DAMAGED));
        assertEquals("破损归还必须填写原因",
                LibraryReturnFormPolicy.missingReasonMessage(LibraryReturnCondition.DAMAGED));
        assertEquals("确认将该馆藏登记为破损并暂停流通？",
                LibraryReturnFormPolicy.confirmation(LibraryReturnCondition.DAMAGED));
    }
}
