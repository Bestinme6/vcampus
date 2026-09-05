package com.vcampus.client.fx.bank;

import com.vcampus.common.model.*;
import javafx.application.Platform;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class BankControllerTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try{Platform.startup(ready::countDown);}catch(IllegalStateException e){ready.countDown();}
        assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);
    }
    @Test void queriesRunOffFxAndLateHomeCannotReplaceLedger() throws Exception {
        QueueExecutor queue=new QueueExecutor(); Fake gateway=new Fake();
        BankController c=fx(()->new BankController(gateway,Set.of(UserRole.STUDENT),queue,()->{},route->{},()->{}));
        fx(()->{c.open("bank");c.open("bank-ledger");return null;});
        queue.last(); fx(()->null);
        queue.first(); fx(()->null);
        assertNotNull(fx(()->c.view().lookup("#bank-ledger-view")));
        assertFalse(gateway.calledOnFx);
        fx(()->{c.close();return null;});
    }
    @Test void uncertainTransferSurvivesNavigationAndRetriesSameId() throws Exception {
        QueueExecutor queue=new QueueExecutor(); Fake gateway=new Fake();gateway.timeout=true;
        BankController c=fx(()->new BankController(gateway,Set.of(UserRole.STUDENT),queue,()->{},route->{},()->{}));
        fx(()->{c.prepareTransfer("teacher","12.30");return null;});queue.first();fx(()->null);
        fx(()->{c.submitOperation();c.submitOperation();return null;});
        assertEquals(1,queue.jobs.size());
        queue.first();fx(()->null);
        String first=gateway.lastId;
        fx(()->{c.deactivate();c.open("bank-transfer");c.submitOperation();return null;});
        gateway.timeout=false;queue.first();fx(()->null);
        assertEquals(first,gateway.lastId);
        assertEquals(2,gateway.transfers);
        assertNotNull(fx(()->{
            new javafx.scene.Scene(c.view(),1100,800);c.view().applyCss();c.view().layout();
            return c.view().lookup("#bank-receipt");
        }));
        fx(()->{c.close();return null;});
    }
    @Test void frozenHomeAndOrdinaryNavigationRespectPermissions() throws Exception {
        fx(()->{
            BankView view=new BankView(Set.of(UserRole.STUDENT),route->{},()->{});
            assertNull(view.lookup("#bank-nav-admin"));
            BankHomeView home=new BankHomeView(route->{},()->{});
            home.show(new BankData.Account(1,"s","同学",new BigDecimal("5.00"),BankAccountStatus.FROZEN,Instant.now()),
                    emptyLedger());
            new javafx.scene.Scene(home,1000,720);home.applyCss();home.layout();
            assertTrue(((Button)home.lookup("#bank-home-transfer")).isDisabled());
            return null;
        });
    }
    @Test void rejectedRetryDoesNotEraseAnEarlierUnknownResult() throws Exception {
        QueueExecutor queue=new QueueExecutor();Fake gateway=new Fake();gateway.timeout=true;
        BankController c=fx(()->new BankController(gateway,Set.of(UserRole.STUDENT),queue,()->{},route->{},()->{}));
        fx(()->{c.prepareTransfer("teacher","12.30");return null;});queue.first();fx(()->null);
        fx(()->{c.submitOperation();return null;});queue.first();fx(()->null);
        gateway.timeout=false;gateway.reject=true;
        fx(()->{c.submitOperation();return null;});queue.first();fx(()->null);
        fx(()->{c.open("bank-transfer");new javafx.scene.Scene(c.view(),1000,720);c.view().applyCss();c.view().layout();return null;});
        assertNotNull(fx(()->c.view().lookup("#bank-operation-retry")));
        fx(()->{c.close();return null;});
    }

    @Test void closedSessionCannotReceiveLateQueryOrPaymentUiUpdates() throws Exception {
        QueueExecutor queue=new QueueExecutor();Fake gateway=new Fake();
        BankController c=fx(()->new BankController(gateway,Set.of(UserRole.STUDENT),queue,()->{},route->{},()->{}));
        fx(()->{c.open("bank");c.close();return null;});queue.first();fx(()->null);
        assertNull(fx(()->c.view().lookup("#bank-home-transfer")));
    }
    static BankData.Ledger emptyLedger(){return new BankData.Ledger(new BankData.Page<>(List.of(),1,10,0),BigDecimal.ZERO,BigDecimal.ZERO);}
    static <T>T fx(Callable<T> work)throws Exception{
        FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);
    }
    static class QueueExecutor implements Executor{
        final Deque<Runnable> jobs=new ArrayDeque<>();public void execute(Runnable job){jobs.add(job);}
        void first(){jobs.removeFirst().run();}void last(){jobs.removeLast().run();}
    }
    static class Fake implements BankGateway{
        boolean calledOnFx,timeout,reject;int transfers;String lastId;
        public BankData.Account account(){calledOnFx|=Platform.isFxApplicationThread();return new BankData.Account(1,"student","同学",new BigDecimal("100.00"),BankAccountStatus.ACTIVE,Instant.now());}
        public BankData.Recipient recipient(String username){return new BankData.Recipient(username,"李老师",false);}
        public BankData.Ledger ledger(BankData.Query query){calledOnFx|=Platform.isFxApplicationThread();return emptyLedger();}
        public BankData.Page<BankData.Account> accounts(String k,BankAccountStatus s,int page){return new BankData.Page<>(List.of(account()),page,10,1);}
        public BankData.Receipt transfer(String u,String a,String id)throws IOException{transfers++;lastId=id;if(timeout)throw new IOException("timeout");if(reject)throw new BankData.Rejected("收款用户已停用");return new BankData.Receipt(new BigDecimal("87.70"),id,false);}
        public BankData.Receipt topUp(String u,String a,String id)throws IOException{return transfer(u,a,id);}
        public void frozen(String u,boolean f){}
        public long order(String reference){return 1;}
    }
}
