package com.vcampus.client.fx.library;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.*;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

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

    private static final class Peer implements AutoCloseable{
        private final ServerSocket server=new ServerSocket(0,10,InetAddress.getLoopbackAddress());
        private final List<String> actions=new CopyOnWriteArrayList<>();private final Thread worker;
        Peer()throws IOException{worker=Thread.ofPlatform().daemon().start(()->{while(!server.isClosed())try(Socket socket=server.accept()){var request=MessageCodec.readRequest(new DataInputStream(socket.getInputStream()));actions.add(request.action());ResponseMessage response=request.action().equals(Actions.LIBRARY_FEE_GET)?ResponseMessage.failure(request.requestId(),"账单不存在或无权查看"):ResponseMessage.success(request.requestId(),"ok",Map.of("page","1","pageSize","10","total","0","count","0"));MessageCodec.writeResponse(new DataOutputStream(socket.getOutputStream()),response);}catch(IOException e){if(!server.isClosed())throw new UncheckedIOException(e);}});}
        int port(){return server.getLocalPort();}public void close()throws Exception{server.close();worker.join(1000);}
    }
}
