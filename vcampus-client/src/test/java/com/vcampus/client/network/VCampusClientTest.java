package com.vcampus.client.network;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class VCampusClientTest {
    @Test void libraryFeeMethodsSendExactActionsAndCoreParameters()throws Exception{
        List<RequestMessage> captured=new CopyOnWriteArrayList<>();
        try(ServerSocket server=new ServerSocket(0);ExecutorService executor=Executors.newSingleThreadExecutor()){
            Future<?> worker=executor.submit(()->{try{for(int i=0;i<11;i++){try(Socket socket=server.accept();DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))){RequestMessage request=MessageCodec.readRequest(in);captured.add(request);MessageCodec.writeResponse(out,ResponseMessage.success(request.requestId(),"ok",Map.of()));out.flush();}}}catch(IOException e){throw new UncheckedIOException(e);}});
            VCampusClient client=new VCampusClient("127.0.0.1",server.getLocalPort());String token="token",op=UUID.randomUUID().toString();
            client.myLibraryFees(token,"PENDING",2);client.getLibraryFee(token,7);client.previewLibraryFeePayment(token,7);client.payLibraryFee(token,7,op);
            client.searchLibraryFees(token,Map.of("keyword","student","page","1"));client.previewLibraryReturnFee(token,"B000000001",LibraryReturnCondition.DAMAGED,LibraryDamageLevel.MAJOR);
            client.waiveLibraryFee(token,7,"原因");client.refundLibraryFee(token,7,"原因",op);client.getLibraryFeePolicy(token);client.updateLibraryFeePolicy(token,Map.of("graceDays","1"));
            client.adminReturnLibraryCopy(token,"B000000001",LibraryReturnCondition.DAMAGED,LibraryDamageLevel.MAJOR,"破损");worker.get(5,TimeUnit.SECONDS);
        }
        assertEquals(List.of(Actions.LIBRARY_FEE_MY,Actions.LIBRARY_FEE_GET,Actions.LIBRARY_FEE_PAYMENT_PREVIEW,Actions.LIBRARY_FEE_PAY,Actions.LIBRARY_ADMIN_FEE_SEARCH,Actions.LIBRARY_ADMIN_FEE_PREVIEW,Actions.LIBRARY_ADMIN_FEE_WAIVE,Actions.LIBRARY_ADMIN_FEE_REFUND,Actions.LIBRARY_ADMIN_FEE_POLICY_GET,Actions.LIBRARY_ADMIN_FEE_POLICY_UPDATE,Actions.LIBRARY_ADMIN_LOAN_RETURN),captured.stream().map(RequestMessage::action).toList());
        assertTrue(captured.stream().allMatch(r->"token".equals(r.parameters().get("sessionToken"))));
        assertEquals("MAJOR",captured.get(5).parameters().get("damageLevel"));assertEquals("2",captured.getFirst().parameters().get("page"));assertEquals("7",captured.get(3).parameters().get("billId"));
    }
}
