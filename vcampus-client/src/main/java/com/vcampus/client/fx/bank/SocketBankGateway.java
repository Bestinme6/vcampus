package com.vcampus.client.fx.bank;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.BankAccountStatus;
import java.io.IOException;
import java.util.Objects;

public final class SocketBankGateway implements BankGateway {
    private final VCampusClient client;
    private final String token;
    public SocketBankGateway(VCampusClient client,String token) {
        this.client=Objects.requireNonNull(client);
        if(token==null||token.isBlank())throw new IllegalArgumentException("登录信息缺失");this.token=token;
    }
    public BankData.Account account() throws IOException {return BankData.account(client.getBankAccount(token));}
    public BankData.Recipient recipient(String username) throws IOException {return BankData.recipient(client.getBankRecipient(token,username));}
    public BankData.Ledger ledger(BankData.Query q) throws IOException {
        return BankData.ledger(client.queryBankLedger(token,q.administrative(),q.username(),
                q.type()==null?"":q.type().name(),q.keyword(),q.from()==null?"":q.from().toString(),
                q.to()==null?"":q.to().toString(),q.reference(),q.page()));
    }
    public BankData.Page<BankData.Account> accounts(String keyword,BankAccountStatus status,int page) throws IOException {
        return BankData.accounts(client.searchBankAccounts(token,keyword,status==null?"":status.name(),page));
    }
    public BankData.Receipt transfer(String username,String amount,String operationId) throws IOException {
        return BankData.receipt(client.transferBank(token,username,amount,operationId));
    }
    public BankData.Receipt topUp(String username,String amount,String operationId) throws IOException {
        return BankData.receipt(client.topUpBankAccount(token,username,amount,operationId));
    }
    public void frozen(String username,boolean frozen) throws IOException {
        BankData.require(client.setBankAccountFrozen(token,username,frozen));
    }
    public long order(String reference) throws IOException {
        var response=client.getBankLedgerOrder(token,reference);BankData.require(response);
        return BankData.id(response.data().get("orderId"));
    }
}
