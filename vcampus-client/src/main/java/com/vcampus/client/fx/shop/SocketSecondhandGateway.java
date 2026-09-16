package com.vcampus.client.fx.shop;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SocketSecondhandGateway implements SecondhandGateway {
    private final VCampusClient client;private final String token;
    public SocketSecondhandGateway(VCampusClient client,String token){this.client=Objects.requireNonNull(client,"client");if(token==null||token.isBlank())throw new IllegalArgumentException("token");this.token=token;}
    @Override public SecondhandData.ListingPage search(String keyword,int page)throws IOException{return SecondhandData.listings(send(Actions.SHOP_USED_ITEM_SEARCH,Map.of("keyword",Objects.requireNonNullElse(keyword,""),"page",Integer.toString(page))));}
    @Override public SecondhandData.Listing get(long itemId)throws IOException{return SecondhandData.listing(send(Actions.SHOP_USED_ITEM_GET,Map.of("itemId",Long.toString(itemId))));}
    @Override public ShopData.ImageChunk imageChunk(long imageId,ShopData.ImageVariant variant,int chunkIndex)throws IOException{return ShopData.imageChunk(send(Actions.SHOP_USED_IMAGE_GET_CHUNK,Map.of("imageId",Long.toString(imageId),"variant",variant.name(),"chunkIndex",Integer.toString(chunkIndex))));}
    @Override public SecondhandData.Receipt buy(long itemId,String operationId,ShippingAddressChoice address)throws IOException{Map<String,String>v=new LinkedHashMap<>();v.put("itemId",Long.toString(itemId));v.put("operationId",operationId);address(v,address);return SecondhandData.receipt(send(Actions.SHOP_USED_ORDER_BUY,v));}
    @Override public SecondhandData.OrderPage orders(boolean selling,int page)throws IOException{return SecondhandData.orders(send(Actions.SHOP_USED_ORDER_SEARCH,Map.of("selling",Boolean.toString(selling),"page",Integer.toString(page))));}
    @Override public SecondhandData.Order order(long orderId)throws IOException{return SecondhandData.order(send(Actions.SHOP_USED_ORDER_GET,Map.of("orderId",Long.toString(orderId))));}
    @Override public SecondhandData.Receipt cancel(long orderId)throws IOException{return receiptFromRow(send(Actions.SHOP_USED_ORDER_CANCEL,Map.of("orderId",Long.toString(orderId))));}
    @Override public SecondhandData.Receipt confirm(long orderId)throws IOException{return receiptFromRow(send(Actions.SHOP_USED_ORDER_CONFIRM,Map.of("orderId",Long.toString(orderId))));}
    private SecondhandData.Receipt receiptFromRow(ResponseMessage response)throws IOException{SecondhandData.Order row=SecondhandData.order(response);return new SecondhandData.Receipt(row.id(),row.orderNo(),row.status(),false);}
    private ResponseMessage send(String action,Map<String,String>values)throws IOException{Map<String,String>all=new LinkedHashMap<>(values);all.put("sessionToken",token);try{return client.send(RequestMessage.create(action,all));}catch(ShopTransportException e){throw e;}catch(IOException e){throw new ShopTransportException(e);}}
    private static void address(Map<String,String>v,ShippingAddressChoice choice){Objects.requireNonNull(choice,"address");if(choice.inline()!=null){v.put("addressMode","inline");v.put("recipient",choice.inline().recipient());v.put("phone",choice.inline().phone());v.put("address",choice.inline().address());}else{v.put("addressMode","saved");v.put("addressSlot",Integer.toString(choice.slot()));v.put("addressVersion",choice.version());}}
}
