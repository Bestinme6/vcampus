package com.vcampus.client.fx.bank;

import com.vcampus.common.model.BankLedgerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankUiVocabularyTest {
    @Test void forumBoostLedgerHasUserFacingLabel() {
        assertEquals("论坛助力",BankUi.type(BankLedgerType.FORUM_BOOST));
    }
}
