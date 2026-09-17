package com.vcampus.client.fx.shop;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.SecondhandCategory;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SocketSecondhandGateway implements SecondhandGateway {
    private final VCampusClient client;private final String token;
    public SocketSecondhandGateway(VCampusClient client,String token){this.client=Objects.requireNonNull(client,"client");if(token==null||token.isBlank())throw new IllegalArgumentException("token");this.token=token;}
    @Override public SecondhandData.ListingPage search(String keyword,int page)throws IOException{return search(keyword,null,page);}
    @Override public SecondhandData.ListingPage search(String keyword,SecondhandCategory category,int page)throws IOException{return SecondhandData.listings(send(Actions.SHOP_USED_ITEM_SEARCH,Map.of("keyword",Objects.requireNonNullElse(keyword,""),"category",category==null?"":category.name(),"page",Integer.toString(page))));}
    @Override public SecondhandData.Listing get(long itemId)throws IOException{return SecondhandData.listing(send(Actions.SHOP_USED_ITEM_GET,Map.of("itemId",Long.toString(itemId))));}
    @Override public ShopData.ImageChunk imageChunk(long imageId,ShopData.ImageVariant variant,int chunkIndex)throws IOException{return ShopData.imageChunk(send(Actions.SHOP_USED_IMAGE_GET_CHUNK,Map.of("imageId",Long.toString(imageId),"variant",variant.name(),"chunkIndex",Integer.toString(chunkIndex))));}
    @Override public SecondhandData.Receipt buy(long itemId,String operationId,ShippingAddressChoice address)throws IOException{Map<String,String>v=new LinkedHashMap<>();v.put("itemId",Long.toString(itemId));v.put("operationId",operationId);address(v,address);return SecondhandData.receipt(send(Actions.SHOP_USED_ORDER_BUY,v));}
    @Override public SecondhandData.OrderPage orders(boolean selling,int page)throws IOException{return SecondhandData.orders(send(Actions.SHOP_USED_ORDER_SEARCH,Map.of("selling",Boolean.toString(selling),"page",Integer.toString(page))));}
    @Override public SecondhandData.Order order(long orderId)throws IOException{return SecondhandData.order(send(Actions.SHOP_USED_ORDER_GET,Map.of("orderId",Long.toString(orderId))));}
    @Override public SecondhandData.Receipt cancel(long orderId)throws IOException{return receiptFromRow(send(Actions.SHOP_USED_ORDER_CANCEL,Map.of("orderId",Long.toString(orderId))));}
    @Override public SecondhandData.Receipt confirm(long orderId)throws IOException{return receiptFromRow(send(Actions.SHOP_USED_ORDER_CONFIRM,Map.of("orderId",Long.toString(orderId))));}
    @Override public long create(SecondhandData.ListingDraft draft)throws IOException{return SecondhandData.createdItem(send(Actions.SHOP_USED_ITEM_CREATE,draft(draft)));}
    @Override public SecondhandData.Listing update(long itemId,SecondhandData.ListingDraft draft)throws IOException{Map<String,String>v=new LinkedHashMap<>(draft(draft));v.put("itemId",Long.toString(itemId));return SecondhandData.listing(send(Actions.SHOP_USED_ITEM_UPDATE,v));}
    @Override public SecondhandData.Listing withdraw(long itemId)throws IOException{return SecondhandData.listing(send(Actions.SHOP_USED_ITEM_WITHDRAW,Map.of("itemId",Long.toString(itemId))));}
    @Override public SecondhandData.UploadTicket uploadStart(long itemId,String mimeType,long expectedBytes)throws IOException{return SecondhandData.uploadTicket(send(Actions.SHOP_USED_IMAGE_UPLOAD_START,Map.of("itemId",Long.toString(itemId),"mimeType",mimeType,"expectedBytes",Long.toString(expectedBytes))));}
    @Override public void uploadChunk(String uploadId,int chunkIndex,byte[] content)throws IOException{require(send(Actions.SHOP_USED_IMAGE_UPLOAD_CHUNK,Map.of("uploadId",uploadId,"chunkIndex",Integer.toString(chunkIndex),"contentBase64",Base64.getEncoder().encodeToString(content))));}
    @Override public void uploadComplete(String uploadId)throws IOException{require(send(Actions.SHOP_USED_IMAGE_UPLOAD_COMPLETE,Map.of("uploadId",uploadId)));}
    @Override public List<ShopData.ImageRef> uploadImages(long itemId,List<SecondhandData.ImagePlanItem> plan)throws IOException{if(plan==null||plan.size()>5)throw new IllegalArgumentException("图片计划无效");var existing=new LinkedHashSet<Long>();var uploads=new LinkedHashSet<String>();int covers=0;Map<String,String>v=new LinkedHashMap<>();v.put("itemId",Long.toString(itemId));v.put("itemCount",Integer.toString(plan.size()));for(int i=0;i<plan.size();i++){var item=plan.get(i);if(item.existingImageId()!=null&&!existing.add(item.existingImageId())||item.uploadId()!=null&&!uploads.add(item.uploadId()))throw new IllegalArgumentException("图片计划无效");if(item.cover())covers++;v.put("item."+i,RowCodec.encode(item.existingImageId()==null?"":item.existingImageId().toString(),item.uploadId()==null?"":item.uploadId(),Boolean.toString(item.cover())));}if(!plan.isEmpty()&&covers!=1)throw new IllegalArgumentException("图片计划无效");return SecondhandData.images(send(Actions.SHOP_USED_IMAGE_COMMIT,v));}
    @Override public SecondhandData.Receipt ship(long orderId)throws IOException{return receiptFromRow(send(Actions.SHOP_USED_ORDER_SHIP,Map.of("orderId",Long.toString(orderId))));}
    @Override public SecondhandData.ReviewPage reviews(long sellerId,int page)throws IOException{return SecondhandData.reviews(send(Actions.SHOP_USED_REVIEW_LIST,Map.of("sellerId",Long.toString(sellerId),"page",Integer.toString(page))));}
    @Override public SecondhandData.Review createReview(SecondhandData.ReviewDraft draft)throws IOException{Objects.requireNonNull(draft,"draft");Map<String,String>v=new LinkedHashMap<>();v.put("operationId",draft.operationId());v.put("orderId",Long.toString(draft.orderId()));v.put("rating",Integer.toString(draft.rating()));v.put("content",draft.content());v.put("imageCount",Integer.toString(draft.uploadIds().size()));for(int i=0;i<draft.uploadIds().size();i++)v.put("image."+i,draft.uploadIds().get(i));return SecondhandData.reviewMutation(send(Actions.SHOP_USED_REVIEW_CREATE,v));}
    @Override public SecondhandData.LikeResult likeReview(long reviewId,boolean enabled)throws IOException{return SecondhandData.like(send(Actions.SHOP_USED_REVIEW_LIKE_SET,Map.of("reviewId",Long.toString(reviewId),"enabled",Boolean.toString(enabled))));}
    @Override public SecondhandData.ReviewUploadTicket reviewUploadStart(String mimeType,long byteSize)throws IOException{return SecondhandData.reviewUploadTicket(send(Actions.SHOP_USED_REVIEW_IMAGE_UPLOAD_START,Map.of("mimeType",mimeType,"byteSize",Long.toString(byteSize))));}
    @Override public void reviewUploadChunk(String uploadId,int chunkIndex,byte[] content)throws IOException{require(send(Actions.SHOP_USED_REVIEW_IMAGE_UPLOAD_CHUNK,Map.of("uploadId",uploadId,"chunkIndex",Integer.toString(chunkIndex),"contentBase64",Base64.getEncoder().encodeToString(content))));}
    @Override public void reviewUploadComplete(String uploadId)throws IOException{require(send(Actions.SHOP_USED_REVIEW_IMAGE_UPLOAD_COMPLETE,Map.of("uploadId",uploadId)));}
    @Override public SecondhandData.ListingPage adminItems(String keyword,int page)throws IOException{return SecondhandData.adminListings(send(Actions.SHOP_USED_ADMIN_ITEM_SEARCH,Map.of("keyword",Objects.requireNonNullElse(keyword,""),"page",Integer.toString(page))));}
    @Override public boolean adminHideItem(long itemId,String reason)throws IOException{return moderateItem(itemId,reason,true);}
    @Override public boolean adminRestoreItem(long itemId,String reason)throws IOException{return moderateItem(itemId,reason,false);}
    @Override public SecondhandData.OrderPage adminOrders(String keyword,ShopOrderStatus status,int page)throws IOException{return SecondhandData.orders(send(Actions.SHOP_USED_ADMIN_ORDER_SEARCH,Map.of("keyword",Objects.requireNonNullElse(keyword,""),"status",status==null?"":status.name(),"page",Integer.toString(page))));}
    @Override public SecondhandData.Order adminOrder(long orderId)throws IOException{return SecondhandData.order(send(Actions.SHOP_USED_ADMIN_ORDER_GET,Map.of("orderId",Long.toString(orderId))));}
    @Override public SecondhandData.Order adminResolve(long orderId,SecondhandData.AdminResolution resolution,String operationId,String reason)throws IOException{return SecondhandData.order(send(Actions.SHOP_USED_ADMIN_ORDER_RESOLVE,Map.of("orderId",Long.toString(orderId),"resolution",resolution.name(),"operationId",operationId,"reason",Objects.requireNonNullElse(reason,""))));}
    @Override public SecondhandData.ReviewPage adminReviews(String keyword,ShopReviewStatus status,int page)throws IOException{return SecondhandData.reviews(send(Actions.SHOP_USED_ADMIN_REVIEW_SEARCH,Map.of("keyword",Objects.requireNonNullElse(keyword,""),"status",status==null?"":status.name(),"page",Integer.toString(page))));}
    @Override public boolean adminHideReview(long reviewId,String reason)throws IOException{return moderateReview(reviewId,reason,true);}
    @Override public boolean adminRestoreReview(long reviewId,String reason)throws IOException{return moderateReview(reviewId,reason,false);}
    private SecondhandData.Receipt receiptFromRow(ResponseMessage response)throws IOException{SecondhandData.Order row=SecondhandData.order(response);return new SecondhandData.Receipt(row.id(),row.orderNo(),row.status(),false);}
    private ResponseMessage send(String action,Map<String,String>values)throws IOException{Map<String,String>all=new LinkedHashMap<>(values);all.put("sessionToken",token);try{return client.send(RequestMessage.create(action,all));}catch(ShopTransportException e){throw e;}catch(IOException e){throw new ShopTransportException(e);}}
    private static Map<String,String> draft(SecondhandData.ListingDraft draft){Objects.requireNonNull(draft,"draft");return Map.of("title",Objects.requireNonNullElse(draft.title(),""),"description",Objects.requireNonNullElse(draft.description(),""),"category",Objects.requireNonNullElse(draft.category(),""),"condition",Objects.requireNonNullElse(draft.condition(),""),"price",draft.price()==null?"":draft.price().toPlainString());}
    private boolean moderateItem(long itemId,String reason,boolean hide)throws IOException{SecondhandData.listing(send(hide?Actions.SHOP_USED_ADMIN_ITEM_HIDE:Actions.SHOP_USED_ADMIN_ITEM_RESTORE,Map.of("itemId",Long.toString(itemId),"reason",Objects.requireNonNullElse(reason,""))));return true;}
    private boolean moderateReview(long reviewId,String reason,boolean hide)throws IOException{return SecondhandData.changed(send(hide?Actions.SHOP_USED_ADMIN_REVIEW_HIDE:Actions.SHOP_USED_ADMIN_REVIEW_RESTORE,Map.of("reviewId",Long.toString(reviewId),"reason",Objects.requireNonNullElse(reason,""))));}
    private static void require(ResponseMessage response)throws IOException{if(!response.success())throw new ShopData.Rejected(response.message());}
    private static void address(Map<String,String>v,ShippingAddressChoice choice){Objects.requireNonNull(choice,"address");if(choice.inline()!=null){v.put("addressMode","inline");v.put("recipient",choice.inline().recipient());v.put("phone",choice.inline().phone());v.put("address",choice.inline().address());}else{v.put("addressMode","saved");v.put("addressSlot",Integer.toString(choice.slot()));v.put("addressVersion",choice.version());}}
}
