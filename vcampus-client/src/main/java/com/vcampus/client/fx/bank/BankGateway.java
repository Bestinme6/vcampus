package com.vcampus.client.fx.bank;

import java.io.IOException;
import com.vcampus.common.model.BankAccountStatus;

public interface BankGateway {
    BankData.Account account() throws IOException;
    BankData.Recipient recipient(String username) throws IOException;
    BankData.Ledger ledger(BankData.Query query) throws IOException;
    BankData.Page<BankData.Account> accounts(String keyword, BankAccountStatus status, int page) throws IOException;
    BankData.Receipt transfer(String username, String amount, String operationId) throws IOException;
    BankData.Receipt topUp(String username, String amount, String operationId) throws IOException;
    void frozen(String username, boolean frozen) throws IOException;
    long order(String reference) throws IOException;
}
