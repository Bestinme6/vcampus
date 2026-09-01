package com.vcampus.client.fx.library;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.LibraryCirculationOperation;
import com.vcampus.common.model.LibraryReturnCondition;
import com.vcampus.common.model.LibrarySort;
import com.vcampus.common.protocol.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Window;
import javafx.embed.swing.SwingFXUtils;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class LibraryAdminViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try {Platform.startup(ready::countDown);} catch(IllegalStateException e) {ready.countDown();}
        assertTrue(ready.await(15,TimeUnit.SECONDS));Platform.setImplicitExit(false);
    }
    static <T>T fx(Callable<T> work)throws Exception {
        FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);
    }
    static void await(Callable<Boolean> condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){if(fx(condition))return;Thread.sleep(20);}fail("UI did not settle");
    }
    static Button button(LibraryAdminView view,String id){return (Button)view.lookup("#"+id);}
    static TextField field(LibraryAdminView view,String id){return (TextField)view.lookup("#"+id);}
    static LibraryAdminView view(Peer peer,Executor executor)throws Exception {
        return fx(()->{var view=new LibraryAdminView(new VCampusClient("127.0.0.1",peer.port()),"test-session",executor,()->{});
            new Scene(view,1000,720);view.applyCss();view.layout();return view;});
    }
    @Test void managementNavigationReachesCirculationAndGlobalLoans()throws Exception {
        try(var peer=new Peer(LibraryAdminViewTest::empty);var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);
            fx(()->{((ToggleButton)view.lookup("#library-admin-nav-circulation")).fire();
                assertNotNull(view.lookup("#library-admin-username"));
                assertTrue(((ToggleButton)view.lookup("#library-admin-nav-circulation")).isSelected());
                ((ToggleButton)view.lookup("#library-admin-nav-loans")).fire();return null;});
            await(()->peer.requests.size()==1&&view.lookup("#library-admin-loan-search")!=null);
            assertEquals(Actions.LIBRARY_ADMIN_LOAN_SEARCH,peer.requests.getFirst().action());
            fx(()->{assertTrue(((ToggleButton)view.lookup("#library-admin-nav-loans")).isSelected());view.close();return null;});
        }
    }
    @Test void catalogIsLazyAndSortAndCategoryAreSentToServer()throws Exception {
        try(var peer=new Peer(r->empty(r));var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);
            assertEquals(0,peer.requests.size());
            fx(()->{view.activate("inventory");return null;});
            await(()->peer.requests.size()==1&&!button(view,"library-admin-book-search").isDisabled());
            fx(()->{field(view,"library-admin-category").setText("文学");
                @SuppressWarnings("unchecked") ComboBox<LibrarySort> sort=(ComboBox<LibrarySort>)view.lookup("#library-admin-sort");
                sort.setValue(LibrarySort.BORROW_COUNT_DESC);
                button(view,"library-admin-book-search").fire();return null;});
            await(()->peer.requests.size()==2);
            assertEquals("文学",peer.requests.get(1).parameters().get("category"));
            assertEquals("BORROW_COUNT_DESC",peer.requests.get(1).parameters().get("sort"));
            assertTrue(peer.requests.stream().allMatch(r->r.action().equals(Actions.LIBRARY_CATALOG_SEARCH)));
            fx(()->{view.close();return null;});
        }
    }
    @Test void changingBorrowerInvalidatesSuccessfulCirculationPreview()throws Exception {
        try(var peer=new Peer(LibraryAdminViewTest::preview);var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);
            fx(()->{view.activate("circulation");field(view,"library-admin-username").setText("student");
                field(view,"library-admin-barcode").setText("COPY-1");button(view,"library-admin-preview").fire();return null;});
            await(()->!button(view,"library-admin-execute").isDisabled());
            fx(()->{field(view,"library-admin-username").setText("another");
                assertTrue(button(view,"library-admin-execute").isDisabled());view.close();return null;});
            assertEquals(1,peer.requests.size());
        }
    }
    @Test void queuedRequestCannotRunAfterClose()throws Exception {
        try(var peer=new Peer(LibraryAdminViewTest::empty)) {
            var queued=new LinkedBlockingQueue<Runnable>();var view=view(peer,queued::add);
            fx(()->{view.activate("loans");view.close();return null;});
            Runnable pending=queued.poll(1,TimeUnit.SECONDS);assertNotNull(pending);pending.run();
            fx(()->{assertTrue(view.isDisabled());return null;});
            assertEquals(0,peer.requests.size());
        }
    }
    @Test void failedQueryShowsServerErrorAndCanBeRetried()throws Exception {
        try(var peer=new Peer(r->ResponseMessage.failure(r.requestId(),"图书查询暂不可用"));var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);fx(()->{view.activate("loans");return null;});
            await(()->((Label)view.lookup("#library-admin-status")).getText().contains("图书查询暂不可用"));
            fx(()->{assertFalse(button(view,"library-admin-loan-search").isDisabled());button(view,"library-admin-loan-search").fire();return null;});
            await(()->peer.requests.size()==2);fx(()->{view.close();return null;});
        }
    }
    @ParameterizedTest @EnumSource(LibraryReturnCondition.class)
    void returnConditionsUseVerifiedBarcodeAndRequireReasonForExceptions(LibraryReturnCondition condition)throws Exception {
        try(var peer=new Peer(r->r.action().equals(Actions.LIBRARY_ADMIN_CIRCULATION_PREVIEW)?preview(r):ResponseMessage.success(r.requestId(),"办理成功",Map.of()));
            var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);
            fx(()->{view.activate("circulation");
                @SuppressWarnings("unchecked") ComboBox<LibraryCirculationOperation> operation=(ComboBox<LibraryCirculationOperation>)view.lookup("#library-admin-operation");
                operation.setValue(LibraryCirculationOperation.RETURN);
                field(view,"library-admin-username").setText("student");field(view,"library-admin-barcode").setText("COPY-1");
                button(view,"library-admin-preview").fire();return null;});
            await(()->!button(view,"library-admin-execute").isDisabled());
            fx(()->{
                @SuppressWarnings("unchecked") ComboBox<LibraryReturnCondition> selector=(ComboBox<LibraryReturnCondition>)view.lookup("#library-admin-condition");
                selector.setValue(condition);
                if(condition!=LibraryReturnCondition.NORMAL){button(view,"library-admin-execute").fire();
                    assertTrue(((Label)view.lookup("#library-admin-status")).getText().contains("必须填写原因"));
                    assertEquals(1,peer.requests.size());field(view,"library-admin-return-reason").setText("登记情况");}
                Platform.runLater(LibraryAdminViewTest::acceptDialog);
                button(view,"library-admin-execute").fire();return null;
            });
            await(()->peer.requests.size()==2);
            var sent=peer.requests.get(1);assertEquals(Actions.LIBRARY_ADMIN_LOAN_RETURN,sent.action());
            assertEquals("COPY-1",sent.parameters().get("barcode"));assertEquals(condition.name(),sent.parameters().get("condition"));
            assertEquals(condition==LibraryReturnCondition.NORMAL?"":"登记情况",sent.parameters().get("reason"));
            fx(()->{view.close();return null;});
        }
    }
    @Test void closingDuringConfirmationCannotSubmitBorrow()throws Exception {
        try(var peer=new Peer(LibraryAdminViewTest::preview);var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);
            fx(()->{view.activate("circulation");field(view,"library-admin-username").setText("student");
                field(view,"library-admin-barcode").setText("COPY-1");button(view,"library-admin-preview").fire();return null;});
            await(()->!button(view,"library-admin-execute").isDisabled());
            fx(()->{Platform.runLater(view::close);button(view,"library-admin-execute").fire();return null;});
            assertEquals(1,peer.requests.size(),"closing a nested modal loop must not continue a mutation");
        }
    }
    @Test void borrowConfirmationSubmitsCheckedIdentityAndBarcode()throws Exception {
        try(var peer=new Peer(r->r.action().equals(Actions.LIBRARY_ADMIN_CIRCULATION_PREVIEW)?preview(r):ResponseMessage.success(r.requestId(),"办理成功",Map.of()));
            var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);
            fx(()->{view.activate("circulation");field(view,"library-admin-username").setText("student");
                field(view,"library-admin-barcode").setText("COPY-1");button(view,"library-admin-preview").fire();return null;});
            await(()->!button(view,"library-admin-execute").isDisabled());
            fx(()->{Platform.runLater(LibraryAdminViewTest::acceptDialog);button(view,"library-admin-execute").fire();return null;});
            await(()->peer.requests.size()==2);
            assertEquals(Actions.LIBRARY_ADMIN_LOAN_BORROW,peer.requests.get(1).action());
            assertEquals("student",peer.requests.get(1).parameters().get("username"));
            assertEquals("COPY-1",peer.requests.get(1).parameters().get("barcode"));
            fx(()->{assertTrue(button(view,"library-admin-execute").isDisabled());view.close();return null;});
        }
    }
    @Test void switchingPagesDiscardsQueuedObsoleteRequest()throws Exception {
        try(var peer=new Peer(LibraryAdminViewTest::empty)) {
            var queued=new LinkedBlockingQueue<Runnable>();var view=view(peer,queued::add);
            fx(()->{view.activate("inventory");view.activate("loans");return null;});
            Runnable old=queued.poll(1,TimeUnit.SECONDS),current=queued.poll(1,TimeUnit.SECONDS);
            assertNotNull(old);assertNotNull(current);old.run();current.run();
            await(()->!button(view,"library-admin-loan-search").isDisabled());
            assertEquals(List.of(Actions.LIBRARY_ADMIN_LOAN_SEARCH),peer.requests.stream().map(RequestMessage::action).toList());
            fx(()->{view.close();return null;});
        }
    }
    @Test void onLoanCopyCannotBeManuallyChanged()throws Exception {
        try(var peer=new Peer(r->r.action().equals(Actions.LIBRARY_ADMIN_COPY_SEARCH)?ResponseMessage.success(r.requestId(),"ok",
                Map.of("page","1","pageSize","10","count","1","total","1","row.0",
                    RowCodec.encode("1","1","COPY-1","测试图书","A-1","ON_LOAN","","2026-08-31T00:00:00Z"))):empty(r));
            var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);fx(()->{view.activate("inventory");return null;});
            await(()->!button(view,"library-admin-book-search").isDisabled());
            fx(()->{((ToggleButton)view.lookup("#library-admin-copies-tab")).fire();return null;});
            await(()->!button(view,"library-admin-copy-search").isDisabled());
            fx(()->{((TableView<?>)view.lookup("#library-admin-copies")).getSelectionModel().selectFirst();
                assertTrue(button(view,"library-admin-copy-status").isDisabled());view.close();return null;});
            assertEquals(2,peer.requests.size());
        }
    }
    @Test void loanFiltersAndNextPageUseServerPageSize()throws Exception {
        try(var peer=new Peer(r->ResponseMessage.success(r.requestId(),"ok",Map.of("page",r.parameters().get("page"),"pageSize","2","count","0","total","3")));
            var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);fx(()->{view.activate("loans");return null;});
            await(()->!button(view,"library-admin-loan-search").isDisabled());
            fx(()->{field(view,"library-admin-loan-keyword").setText("student");
                ((ComboBox<?>)view.lookup("#library-admin-active")).getSelectionModel().select(1);
                ((ComboBox<?>)view.lookup("#library-admin-overdue")).getSelectionModel().select(1);
                button(view,"library-admin-loan-search").fire();return null;});
            await(()->peer.requests.size()==2&&!button(view,"library-admin-loan-search").isDisabled());
            fx(()->{view.lookupAll(".button").stream().filter(n->n instanceof Button b&&b.getText().equals("下一页")).map(n->(Button)n).findFirst().orElseThrow().fire();return null;});
            await(()->peer.requests.size()==3);
            var sent=peer.requests.get(2);assertEquals("2",sent.parameters().get("page"));assertEquals("true",sent.parameters().get("active"));
            assertEquals("true",sent.parameters().get("overdue"));assertEquals("student",sent.parameters().get("keyword"));
            fx(()->{view.close();return null;});
        }
    }
    @Test void administrationRendersAtDesktopAndCompactSizes()throws Exception {
        try(var peer=new Peer(r->{var rows=new LinkedHashMap<String,String>();rows.putAll(Map.of("page","1","pageSize","10","total","4","count","4"));
                String[] titles={"Java 核心技术","数据库系统概论","活着","人类简史"};
                for(int i=0;i<4;i++){rows.put("row."+i,RowCodec.encode(Integer.toString(i+1),"BK000"+(i+1),"9780000000000",titles[i],"演示作者","演示出版社","2024",i<2?"计算机":"文学","演示图书简介","true","5","3"));
                    rows.put("row."+i+".onLoanCopies","2");rows.put("row."+i+".borrowCount",Integer.toString(18+i*7));}
                return ResponseMessage.success(r.requestId(),"ok",rows);});var executor=Executors.newSingleThreadExecutor()) {
            var view=view(peer,executor);fx(()->{view.activate("inventory");return null;});
            await(()->!button(view,"library-admin-book-search").isDisabled());
            fx(()->{
                var stylesheet=LibraryAdminView.class.getResource("/com/vcampus/client/fx/library/library.css");
                if(stylesheet!=null)view.getScene().getStylesheets().add(stylesheet.toExternalForm());
                Path directory=Path.of("target","library-screenshots");Files.createDirectories(directory);
                for(int width:new int[]{1440,1000}){view.resize(width,width==1440?1024:720);view.applyCss();view.layout();
                    TableView<?> table=(TableView<?>)view.lookup("#library-admin-books");assertTrue(table.getHeight()>=260);
                    assertTrue(button(view,"library-admin-book-create").getWidth()>80);
                    ImageIO.write(SwingFXUtils.fromFXImage(view.snapshot(null,null),null),"png",directory.resolve("library-admin-"+width+".png").toFile());}
                view.close();return null;
            });
        }
    }
    private static void acceptDialog(){
        var window=List.copyOf(Window.getWindows()).stream().filter(w->w.isShowing()&&w.getScene().getRoot() instanceof DialogPane).findFirst().orElseThrow();
        DialogPane pane=(DialogPane)window.getScene().getRoot();((Button)pane.lookupButton(ButtonType.OK)).fire();
    }
    private static ResponseMessage empty(RequestMessage r){return ResponseMessage.success(r.requestId(),"ok",Map.of("page","1","pageSize","10","count","0","total","0"));}
    private static ResponseMessage preview(RequestMessage r) {
        return ResponseMessage.success(r.requestId(),"ok",Map.ofEntries(Map.entry("borrowerUserId","1"),Map.entry("username","student"),
            Map.entry("displayName","测试同学"),Map.entry("baseIdentity","STUDENT"),Map.entry("copyId","1"),Map.entry("bookId","1"),
            Map.entry("title","测试图书"),Map.entry("barcode","COPY-1"),Map.entry("copyStatus","AVAILABLE"),Map.entry("activeLoanId",""),
            Map.entry("activeLoans","0"),Map.entry("maxLoans","5"),Map.entry("overdue","false"),Map.entry("allowed","true"),Map.entry("message","可以借阅")));
    }
    private static final class Peer implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,20,InetAddress.getLoopbackAddress());
        final List<RequestMessage> requests=new CopyOnWriteArrayList<>();final Thread worker;
        Peer(Function<RequestMessage,ResponseMessage> response)throws IOException {
            worker=Thread.ofPlatform().daemon().start(()->{while(!server.isClosed())try(Socket socket=server.accept()){
                var request=MessageCodec.readRequest(new DataInputStream(socket.getInputStream()));requests.add(request);
                MessageCodec.writeResponse(new DataOutputStream(socket.getOutputStream()),response.apply(request));
            }catch(IOException e){if(!server.isClosed())throw new UncheckedIOException(e);}});
        }
        int port(){return server.getLocalPort();}
        @Override public void close()throws Exception{server.close();worker.join(1000);}
    }
}
