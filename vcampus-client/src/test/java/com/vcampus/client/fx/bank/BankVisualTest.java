package com.vcampus.client.fx.bank;

import com.vcampus.common.model.*;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.*;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BankVisualTest {
    private static final Path OUTPUT=Path.of("..","docs","design","javafx-bank","screenshots");
    @BeforeAll static void toolkit()throws Exception{
        CountDownLatch ready=new CountDownLatch(1);try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}
        assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);
    }
    @Test void renderNativePagesAtActualCampusContentWidths()throws Exception{
        FutureTask<Void> task=new FutureTask<>(()->{
            for(int width:new int[]{780,1060,1220}){
                BankView root=shell();BankHomeView home=new BankHomeView(r->{},()->{});
                home.show(account(BankAccountStatus.ACTIVE),ledger());root.page(home,"bank");capture(root,"home-"+width,width,760);
                var transfer=new BankTransferView((u,a)->{});transfer.form();root.page(transfer,"bank-transfer");capture(root,"transfer-"+width,width,760);
                var operation=new BankOperation(BankOperation.Kind.TRANSFER,"2026000002","李同学","100.00");
                transfer.confirm(operation,new BigDecimal("2680.00"),()->{},()->{});capture(root,"confirm-"+width,width,760);
                var ledgerView=new BankLedgerView(q->{},ref->{});ledgerView.configure(BankData.Query.mine());ledgerView.show(ledger());
                ledgerView.detail(ledger().entries().rows().getFirst());root.page(ledgerView,"bank-ledger");capture(root,"ledger-"+width,width,760);
                var admin=new BankAdminView(new BankAdminView.Listener(){
                    public void search(String k,BankAccountStatus s,int p){}public void lookup(String u){}
                    public void topUp(BankData.Recipient r,String a){}public void status(BankData.Recipient r,boolean f){}
                    public void ledger(String u){}
                });
                admin.show(new BankData.Page<>(List.of(account(BankAccountStatus.ACTIVE)),1,10,1));
                admin.select(new BankData.Recipient("2026000001","张同学",false),account(BankAccountStatus.ACTIVE));
                root.page(admin,"bank-admin");capture(root,"admin-"+width,width,760);
            }
            BankView root=shell();BankHomeView frozen=new BankHomeView(r->{},()->{});
            frozen.show(account(BankAccountStatus.FROZEN),ledger());root.page(frozen,"bank");capture(root,"frozen",780,760);
            BankTransferView transfer=new BankTransferView((u,a)->{});var op=new BankOperation(BankOperation.Kind.TRANSFER,"2026000002","李同学","100.00");
            op.begin();op.unknown();transfer.pending(op,()->{},()->{});root.page(transfer,"bank-transfer");capture(root,"uncertain",780,760);
            return null;
        });Platform.runLater(task);task.get(60,TimeUnit.SECONDS);
    }
    private static BankView shell(){return new BankView(Set.of(UserRole.TEACHER,UserRole.BANK_ADMIN),r->{},()->{});}
    private static BankData.Account account(BankAccountStatus status){
        return new BankData.Account(1,"2026000001","张同学",new BigDecimal("2680.00"),status,Instant.parse("2026-09-05T02:28:00Z"));
    }
    private static BankData.Ledger ledger(){
        return new BankData.Ledger(new BankData.Page<>(List.of(
                new BankData.Entry(1,1,BankLedgerType.SHOP_PAYMENT,BankLedgerDirection.DEBIT,new BigDecimal("36.80"),
                        new BigDecimal("2680.00"),"SO-20260905-0001",null,null,"校园商店订单支付",Instant.parse("2026-09-05T02:28:00Z")),
                new BankData.Entry(2,1,BankLedgerType.TRANSFER_IN,BankLedgerDirection.CREDIT,new BigDecimal("200.00"),
                        new BigDecimal("2716.80"),"20260904-TRANSFER",2L,null,"收到转账",Instant.parse("2026-09-04T10:40:00Z"))),
                1,10,2),new BigDecimal("200.00"),new BigDecimal("36.80"));
    }
    private static void capture(Parent root,String name,int width,int height)throws Exception{
        if(root.getScene()!=null)root.getScene().setRoot(new Group());
        Scene scene=new Scene(root,width,height);root.resize(width,height);root.applyCss();root.layout();
        WritableImage image=root.snapshot(null,null);assertEquals(width,image.getWidth(),1);
        Files.createDirectories(OUTPUT);ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",OUTPUT.resolve(name+".png").toFile());
        scene.setRoot(new Group());
    }
}
