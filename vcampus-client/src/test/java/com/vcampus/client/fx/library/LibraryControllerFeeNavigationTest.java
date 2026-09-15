package com.vcampus.client.fx.library;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class LibraryControllerFeeNavigationTest {
    @BeforeAll static void toolkit()throws Exception{LibraryAdminViewTest.toolkit();}

    @Test void missingNotificationBillFallsBackToListWithStableMessage()throws Exception{
        try(Peer peer=new Peer();var executor=Executors.newSingleThreadExecutor()){
            LibraryController controller=LibraryAdminViewTest.fx(()->{var value=new LibraryController(new VCampusClient("127.0.0.1",peer.port()),"token",Set.of(UserRole.STUDENT),executor,()->{},()->{},()->{});new Scene(value.view(),1000,720);value.openFee(9);return value;});
            LibraryAdminViewTest.await(()->{controller.view().applyCss();controller.view().layout();Label status=(Label)controller.view().lookup("#library-status");return peer.actions.size()==2&&status!=null&&status.getText().contains("账单不存在或状态已发生变化");});
            assertEquals(List.of(Actions.LIBRARY_FEE_GET,Actions.LIBRARY_FEE_MY),peer.actions);LibraryAdminViewTest.fx(()->{controller.close();return null;});
        }
    }

    @Test void pendingFeeBorrowFailureUsesDialogAndAcknowledgeOnlyClosesIt()throws Exception{
        try(Peer peer=new Peer(LibraryControllerFeeNavigationTest::pendingFeeFlow);var executor=Executors.newSingleThreadExecutor()){
            LibraryController controller=openCatalog(peer,executor);
            triggerBorrow(controller,peer);
            LibraryAdminViewTest.fx(()->{
                DialogPane pane=showingDialog();
                assertEquals("存在待缴图书费用，暂时不能借书或续借",pane.getContentText());
                assertEquals("",((Label)controller.view().lookup("#library-status")).getText());
                dialogButton(pane,"确定").fire();
                return null;
            });
            LibraryAdminViewTest.await(()->showingDialogOrNull()==null);
            assertEquals(List.of(Actions.LIBRARY_CATALOG_SEARCH,Actions.LIBRARY_LOAN_BORROW),peer.actions);
            LibraryAdminViewTest.fx(()->{controller.close();return null;});
        }
    }

    @Test void pendingFeeDialogCanOpenPendingFeeTab()throws Exception{
        try(Peer peer=new Peer(LibraryControllerFeeNavigationTest::pendingFeeFlow);var executor=Executors.newSingleThreadExecutor()){
            LibraryController controller=openCatalog(peer,executor);
            triggerBorrow(controller,peer);
            LibraryAdminViewTest.fx(()->{dialogButton(showingDialog(),"前往缴费").fire();return null;});
            LibraryAdminViewTest.await(()->{controller.view().applyCss();controller.view().layout();return peer.actions.contains(Actions.LIBRARY_FEE_MY)&&controller.view().lookup("#library-fees")!=null;});
            LibraryAdminViewTest.fx(()->{
                Button fees=(Button)controller.view().lookup("#library-tab-fees");
                assertTrue(fees.getStyleClass().contains("library-tab-selected"));
                controller.close();return null;
            });
        }
    }

    private static LibraryController openCatalog(Peer peer,Executor executor)throws Exception{
        LibraryController controller=LibraryAdminViewTest.fx(()->{
            var value=new LibraryController(new VCampusClient("127.0.0.1",peer.port()),"token",Set.of(UserRole.STUDENT),executor,()->{},()->{},()->{});
            new Scene(value.view(),1000,720);value.open("catalog");return value;
        });
        LibraryAdminViewTest.await(()->{controller.view().applyCss();controller.view().layout();return controller.view().lookup("#library-borrow-7")!=null;});
        return controller;
    }

    private static void triggerBorrow(LibraryController controller,Peer peer)throws Exception{
        LibraryAdminViewTest.fx(()->{
            Platform.runLater(()->((Button)showingDialog().lookupButton(ButtonType.OK)).fire());
            ((Button)controller.view().lookup("#library-borrow-7")).fire();return null;
        });
        LibraryAdminViewTest.await(()->peer.actions.contains(Actions.LIBRARY_LOAN_BORROW)&&showingDialogOrNull()!=null);
    }

    private static DialogPane showingDialog(){return Objects.requireNonNull(showingDialogOrNull());}
    private static DialogPane showingDialogOrNull(){return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene).filter(Objects::nonNull).map(javafx.scene.Scene::getRoot).filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElse(null);}
    private static Button dialogButton(DialogPane pane,String text){return pane.getButtonTypes().stream().map(pane::lookupButton).map(Button.class::cast).filter(button->text.equals(button.getText())).findFirst().orElseThrow();}

    private static ResponseMessage pendingFeeFlow(RequestMessage request){
        return switch(request.action()){
            case Actions.LIBRARY_CATALOG_SEARCH->ResponseMessage.success(request.requestId(),"ok",Map.ofEntries(
                    Map.entry("page","1"),Map.entry("pageSize","10"),Map.entry("total","1"),Map.entry("count","1"),
                    Map.entry("row.0",RowCodec.encode("7","BK000000007","","程序设计","示例作者","示例出版社","2026","计算机","内容介绍","true","1","1")),
                    Map.entry("row.0.onLoanCopies","0"),Map.entry("row.0.borrowCount","2"),Map.entry("row.0.replacementPrice","89.00")));
            case Actions.LIBRARY_LOAN_BORROW->ResponseMessage.failure(request.requestId(),"存在待缴图书费用，暂时不能借书或续借");
            case Actions.LIBRARY_FEE_MY->ResponseMessage.success(request.requestId(),"ok",Map.of("page","1","pageSize","10","total","0","count","0"));
            default->ResponseMessage.failure(request.requestId(),"unexpected action "+request.action());
        };
    }

    private static final class Peer implements AutoCloseable{
        private final ServerSocket server=new ServerSocket(0,10,InetAddress.getLoopbackAddress());
        private final List<String> actions=new CopyOnWriteArrayList<>();private final Thread worker;
        Peer()throws IOException{this(request->request.action().equals(Actions.LIBRARY_FEE_GET)?ResponseMessage.failure(request.requestId(),"账单不存在或无权查看"):ResponseMessage.success(request.requestId(),"ok",Map.of("page","1","pageSize","10","total","0","count","0")));}
        Peer(Function<RequestMessage,ResponseMessage> response)throws IOException{worker=Thread.ofPlatform().daemon().start(()->{while(!server.isClosed())try(Socket socket=server.accept()){var request=MessageCodec.readRequest(new DataInputStream(socket.getInputStream()));actions.add(request.action());MessageCodec.writeResponse(new DataOutputStream(socket.getOutputStream()),response.apply(request));}catch(IOException e){if(!server.isClosed())throw new UncheckedIOException(e);}});}
        int port(){return server.getLocalPort();}public void close()throws Exception{server.close();worker.join(1000);}
    }
}
