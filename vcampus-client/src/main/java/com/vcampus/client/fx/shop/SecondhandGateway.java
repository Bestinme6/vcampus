package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.SecondhandCategory;
import java.io.IOException;
import java.util.List;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopReviewStatus;

public interface SecondhandGateway {
    SecondhandData.ListingPage search(String keyword,int page)throws IOException;
    default SecondhandData.ListingPage search(String keyword,SecondhandCategory category,int page)
            throws IOException{return search(keyword,page);}
    SecondhandData.Listing get(long itemId)throws IOException;
    ShopData.ImageChunk imageChunk(long imageId,ShopData.ImageVariant variant,int chunkIndex)throws IOException;
    SecondhandData.Receipt buy(long itemId,String operationId,ShippingAddressChoice address)throws IOException;
    SecondhandData.OrderPage orders(boolean selling,int page)throws IOException;
    SecondhandData.Order order(long orderId)throws IOException;
    SecondhandData.Receipt cancel(long orderId)throws IOException;
    SecondhandData.Receipt confirm(long orderId)throws IOException;
    default long create(SecondhandData.ListingDraft draft)throws IOException{throw unsupported();}
    default SecondhandData.Listing update(long itemId,SecondhandData.ListingDraft draft)throws IOException{throw unsupported();}
    default SecondhandData.Listing withdraw(long itemId)throws IOException{throw unsupported();}
    default SecondhandData.UploadTicket uploadStart(long itemId,String mimeType,long expectedBytes)throws IOException{throw unsupported();}
    default void uploadChunk(String uploadId,int chunkIndex,byte[] content)throws IOException{throw unsupported();}
    default void uploadComplete(String uploadId)throws IOException{throw unsupported();}
    default List<ShopData.ImageRef> uploadImages(long itemId,List<SecondhandData.ImagePlanItem> plan)throws IOException{throw unsupported();}
    default SecondhandData.Receipt ship(long orderId)throws IOException{throw unsupported();}
    default SecondhandData.ReviewPage reviews(long sellerId,int page)throws IOException{throw unsupported();}
    default SecondhandData.Review createReview(SecondhandData.ReviewDraft draft)throws IOException{throw unsupported();}
    default SecondhandData.LikeResult likeReview(long reviewId,boolean enabled)throws IOException{throw unsupported();}
    default SecondhandData.ReviewUploadTicket reviewUploadStart(String mimeType,long byteSize)throws IOException{throw unsupported();}
    default void reviewUploadChunk(String uploadId,int chunkIndex,byte[] content)throws IOException{throw unsupported();}
    default void reviewUploadComplete(String uploadId)throws IOException{throw unsupported();}
    default boolean adminHideItem(long itemId,String reason)throws IOException{throw unsupported();}
    default boolean adminRestoreItem(long itemId,String reason)throws IOException{throw unsupported();}
    default SecondhandData.OrderPage adminOrders(String keyword,ShopOrderStatus status,int page)throws IOException{throw unsupported();}
    default SecondhandData.Order adminOrder(long orderId)throws IOException{throw unsupported();}
    default SecondhandData.Order adminResolve(long orderId,SecondhandData.AdminResolution resolution,
                                      String operationId,String reason)throws IOException{throw unsupported();}
    default SecondhandData.ReviewPage adminReviews(String keyword,ShopReviewStatus status,int page)throws IOException{throw unsupported();}
    default boolean adminHideReview(long reviewId,String reason)throws IOException{throw unsupported();}
    default boolean adminRestoreReview(long reviewId,String reason)throws IOException{throw unsupported();}
    private static UnsupportedOperationException unsupported(){return new UnsupportedOperationException("当前网关不支持该二手市场操作");}
}
