package com.vcampus.common.model;

import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.MessageCodec;
import com.vcampus.common.protocol.RequestMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankVocabularyTest {
    @Test
    void bankRequestSurvivesTheRealBinaryCodec() throws Exception {
        RequestMessage request = RequestMessage.create(Actions.BANK_TRANSFER_CREATE, Map.of(
                "status", BankAccountStatus.FROZEN.name(),
                "type", BankLedgerType.SHOP_REFUND.name(),
                "direction", BankLedgerDirection.DEBIT.name()));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageCodec.writeRequest(new DataOutputStream(bytes), request);
        RequestMessage decoded = MessageCodec.readRequest(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals("bank.transfer.create", decoded.action());
        assertEquals("FROZEN", decoded.parameters().get("status"));
        assertEquals("SHOP_REFUND", decoded.parameters().get("type"));
        assertEquals("DEBIT", decoded.parameters().get("direction"));
    }

    @Test
    void onlyBankAndSuperAdministratorsCanManageBankAccounts() {
        assertFalse(BankAccessPolicy.canManage(Set.of(UserRole.STUDENT)));
        assertFalse(BankAccessPolicy.canManage(Set.of(UserRole.SHOP_ADMIN)));
        assertTrue(BankAccessPolicy.canManage(Set.of(UserRole.BANK_ADMIN)));
        assertTrue(BankAccessPolicy.canManage(Set.of(UserRole.SUPER_ADMIN)));
    }
}
