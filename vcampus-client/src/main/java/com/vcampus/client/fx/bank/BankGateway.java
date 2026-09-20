package com.vcampus.client.fx.bank;

import java.io.IOException;
import com.vcampus.common.model.BankAccountStatus;

/**
 * 校园银行界面与远程服务之间的用例边界。
 *
 * <p>实现可以访问 Socket，也可以在界面测试中提供内存替身。</p>
 *
 * @since 1.0
 */
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
