package com.vcampus.client.fx.bank;

import com.vcampus.common.model.MoneyPolicy;
import java.math.BigDecimal;
import java.util.UUID;

/** One immutable payment intent; retained across navigation and uncertain responses. FX-thread confined. */
final class BankOperation {
    enum Kind { TRANSFER, TOPUP }
    enum State { READY, SUBMITTING, UNKNOWN, COMPLETE, REJECTED }
    private final Kind kind;
    private final String username, displayName, id = UUID.randomUUID().toString();
    private final BigDecimal amount;
    private State state = State.READY;
    BankOperation(Kind kind, String username, String displayName, String amount) {
        if(username==null||username.isBlank())throw new IllegalArgumentException("请填写目标账号");
        this.kind=java.util.Objects.requireNonNull(kind);
        this.username=username.trim();this.displayName=displayName;
        this.amount=MoneyPolicy.parsePositive(amount);
    }
    boolean begin() {
        if(state!=State.READY&&state!=State.UNKNOWN)return false;
        state=State.SUBMITTING;return true;
    }
    void unknown() { state=State.UNKNOWN; }
    void complete() { state=State.COMPLETE; }
    void reject() { state=State.REJECTED; }
    boolean terminal() { return state==State.COMPLETE||state==State.REJECTED; }
    Kind kind(){return kind;} State state(){return state;}
    String username(){return username;} String displayName(){return displayName;}
    BigDecimal amount(){return amount;} String id(){return id;}
}
