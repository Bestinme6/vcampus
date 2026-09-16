package com.vcampus.client.fx.shop;

import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SecondhandData {
    private SecondhandData() { }

    public enum EscrowStatus { HELD, REFUNDED, SETTLED }
    public enum AdminResolution { REFUND, SETTLE }

    public record Listing(long id,long sellerId,String sellerName,String title,String description,
                          String category,String condition,BigDecimal price,SecondhandItemStatus status,
                          SecondhandModerationStatus moderation,Instant createdAt,Instant updatedAt) { }
    public record ListingPage(List<Listing> rows,int page,int pageSize,int total) {
        public ListingPage { rows=List.copyOf(rows); }
    }
    public record Receipt(long orderId,String orderNo,ShopOrderStatus status,boolean duplicate) { }
    public record Order(long id,String orderNo,long itemId,long buyerId,long sellerId,BigDecimal amount,
                        ShopOrderStatus status,EscrowStatus escrowStatus,String title,String category,
                        String condition,String sellerName,ShippingAddressValue shippingAddress,
                        Instant createdAt,Instant shippedAt,Instant completedAt,Instant cancelledAt) { }
    public record OrderPage(List<Order> rows,int page,int pageSize,int total) {
        public OrderPage { rows=List.copyOf(rows); }
    }
    public record ListingDraft(Long itemId,String title,String description,String category,
                               String condition,BigDecimal price) { }
    public record UploadTicket(String uploadId,long itemId,long expectedBytes,int chunkBytes) { }
    public record ImagePlanItem(Long existingImageId,String uploadId,boolean cover) {
        public ImagePlanItem { if((existingImageId==null)==(uploadId==null||uploadId.isBlank()))throw new IllegalArgumentException("图片计划无效"); }
    }
    public record ReviewImage(long id,int position,String mimeType,long byteSize,String sha256,
                              int width,int height) { }
    public record Review(long id,long orderId,long itemId,long sellerId,long authorId,String authorName,
                         int rating,String content,ShopReviewStatus status,int likeCount,
                         boolean likedByViewer,Instant createdAt,Instant updatedAt,
                         List<ReviewImage> images) {
        public Review { images=List.copyOf(images); }
    }
    public record RatingSummary(BigDecimal average,int count,List<Integer> starCounts) {
        public RatingSummary { starCounts=List.copyOf(starCounts); }
    }
    public record ReviewPage(List<Review> rows,int page,int pageSize,int total,RatingSummary summary) {
        public ReviewPage { rows=List.copyOf(rows); }
    }
    public record ReviewDraft(String operationId,long orderId,int rating,String content,
                              List<String> uploadIds) {
        public ReviewDraft { uploadIds=List.copyOf(uploadIds); }
    }
    public record LikeResult(long reviewId,boolean enabled,boolean changed,int likeCount) { }
    public record ReviewUploadTicket(String uploadId,long expectedBytes,int chunkBytes) { }

    static ListingPage listings(ResponseMessage response) throws IOException {
        Map<String,String> data=data(response);int count=integer(data.get("count"),false);
        List<Listing> rows=new ArrayList<>();for(int i=0;i<count;i++){
            Listing row=listing(data.get("row."+i));
            if(row.status()==SecondhandItemStatus.AVAILABLE
                    &&row.moderation()==SecondhandModerationStatus.VISIBLE)rows.add(row);
        }
        return new ListingPage(rows,integer(data.get("page"),true),
                integer(data.get("pageSize"),true),integer(data.get("total"),false));
    }
    static Listing listing(ResponseMessage response)throws IOException{return listing(required(data(response),"row"));}
    static Receipt receipt(ResponseMessage response)throws IOException{Map<String,String>d=data(response);return new Receipt(
            id(d.get("orderId")),required(d,"orderNo"),enumValue(ShopOrderStatus.class,d.get("status")),
            Boolean.parseBoolean(Objects.requireNonNullElse(d.get("duplicate"),"false")));}
    static OrderPage orders(ResponseMessage response)throws IOException{Map<String,String>d=data(response);int count=integer(d.get("count"),false);List<Order>rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(order(d.get("row."+i)));return new OrderPage(rows,integer(d.get("page"),true),integer(d.get("pageSize"),true),integer(d.get("total"),false));}
    static Order order(ResponseMessage response)throws IOException{return order(required(data(response),"row"));}
    static long createdItem(ResponseMessage response)throws IOException{return id(required(data(response),"itemId"));}
    static List<ShopData.ImageRef> images(ResponseMessage response)throws IOException{return ShopData.imageRefs(response);}
    static UploadTicket uploadTicket(ResponseMessage response)throws IOException{Map<String,String>d=data(response);return new UploadTicket(required(d,"uploadId"),id(d.get("itemId")),positiveLong(d.get("expectedBytes"),"图片大小无效"),positiveInt(d.get("chunkBytes"),"图片分块无效"));}
    static ReviewPage reviews(ResponseMessage response)throws IOException{Map<String,String>d=data(response);int count=integer(d.get("rowCount"),false);List<Review>rows=new ArrayList<>();for(int i=0;i<count;i++){List<ReviewImage>images=new ArrayList<>();int imageCount=integer(Objects.requireNonNullElse(d.get("row."+i+".imageCount"),"0"),false);for(int j=0;j<imageCount;j++)images.add(reviewImage(d.get("row."+i+".image."+j)));rows.add(review(d.get("row."+i),images));}BigDecimal average=d.get("ratingAverage")==null||d.get("ratingAverage").isBlank()?null:new BigDecimal(d.get("ratingAverage")).setScale(1);List<Integer>stars=new ArrayList<>();for(int i=1;i<=5;i++)stars.add(integer(Objects.requireNonNullElse(d.get("ratingStar."+i),"0"),false));return new ReviewPage(rows,integer(d.get("page"),true),integer(d.get("pageSize"),true),integer(d.get("total"),false),new RatingSummary(average,integer(Objects.requireNonNullElse(d.get("ratingCount"),"0"),false),stars));}
    static Review reviewMutation(ResponseMessage response)throws IOException{return review(required(data(response),"row.0"),List.of());}
    static LikeResult like(ResponseMessage response)throws IOException{Map<String,String>d=data(response);return new LikeResult(id(d.get("reviewId")),strictBoolean(d.get("enabled")),strictBoolean(d.get("changed")),integer(d.get("likeCount"),false));}
    static ReviewUploadTicket reviewUploadTicket(ResponseMessage response)throws IOException{Map<String,String>d=data(response);return new ReviewUploadTicket(required(d,"uploadId"),positiveLong(d.get("expectedBytes"),"图片大小无效"),positiveInt(d.get("chunkBytes"),"图片分块无效"));}
    static boolean changed(ResponseMessage response)throws IOException{String value=data(response).get("changed");return value==null||strictBoolean(value);}

    private static Listing listing(String encoded){List<String>f=row(encoded,12,"二手物品数据无效");return new Listing(
            id(f.get(0)),id(f.get(1)),f.get(2),f.get(3),f.get(4),f.get(5),f.get(6),money(f.get(7)),
            enumValue(SecondhandItemStatus.class,f.get(8)),enumValue(SecondhandModerationStatus.class,f.get(9)),
            instant(f.get(10)),instant(f.get(11)));}
    private static Order order(String encoded){List<String>f=row(encoded,19,"二手订单数据无效");return new Order(
            id(f.get(0)),f.get(1),id(f.get(2)),id(f.get(3)),id(f.get(4)),money(f.get(5)),
            enumValue(ShopOrderStatus.class,f.get(6)),enumValue(EscrowStatus.class,f.get(7)),f.get(8),f.get(9),
            f.get(10),f.get(11),new ShippingAddressValue(f.get(12),f.get(13),f.get(14)),instant(f.get(15)),
            nullableInstant(f.get(16)),nullableInstant(f.get(17)),nullableInstant(f.get(18)));}
    private static Review review(String encoded,List<ReviewImage>images){List<String>f=row(encoded,13,"二手评价数据无效");return new Review(id(f.get(0)),id(f.get(1)),id(f.get(2)),id(f.get(3)),id(f.get(4)),f.get(5),positiveInt(f.get(6),"评价评分无效"),f.get(7),enumValue(ShopReviewStatus.class,f.get(8)),integer(f.get(9),false),strictBoolean(f.get(10)),instant(f.get(11)),instant(f.get(12)),images);}
    private static ReviewImage reviewImage(String encoded){List<String>f=row(encoded,7,"二手评价图片数据无效");return new ReviewImage(id(f.get(0)),integer(f.get(1),false),f.get(2),positiveLong(f.get(3),"图片大小无效"),f.get(4),positiveInt(f.get(5),"图片宽度无效"),positiveInt(f.get(6),"图片高度无效"));}
    private static Map<String,String> data(ResponseMessage response)throws IOException{Objects.requireNonNull(response,"response");if(!response.success())throw new ShopData.Rejected(response.message());return response.data();}
    private static String required(Map<String,String>d,String key){String v=d.get(key);if(v==null)throw new IllegalArgumentException("响应数据无效");return v;}
    private static List<String> row(String v,int size,String message){if(v==null)throw new IllegalArgumentException(message);List<String>f=RowCodec.decode(v);if(f.size()!=size)throw new IllegalArgumentException(message);return f;}
    private static long id(String v){try{long n=Long.parseLong(v);if(n<1)throw new NumberFormatException();return n;}catch(RuntimeException e){throw new IllegalArgumentException("编号数据无效");}}
    private static int integer(String v,boolean positive){try{int n=Integer.parseInt(v);if(positive?n<1:n<0)throw new NumberFormatException();return n;}catch(RuntimeException e){throw new IllegalArgumentException("分页数据无效");}}
    private static int positiveInt(String v,String message){try{int n=Integer.parseInt(v);if(n<1)throw new NumberFormatException();return n;}catch(RuntimeException e){throw new IllegalArgumentException(message);}}
    private static long positiveLong(String v,String message){try{long n=Long.parseLong(v);if(n<1)throw new NumberFormatException();return n;}catch(RuntimeException e){throw new IllegalArgumentException(message);}}
    private static boolean strictBoolean(String value){if(!"true".equals(value)&&!"false".equals(value))throw new IllegalArgumentException("响应数据无效");return Boolean.parseBoolean(value);}
    private static BigDecimal money(String v){try{BigDecimal n=new BigDecimal(v).setScale(2);if(n.signum()<0)throw new NumberFormatException();return n;}catch(RuntimeException e){throw new IllegalArgumentException("金额数据无效");}}
    private static Instant instant(String v){try{return Instant.parse(v);}catch(RuntimeException e){throw new IllegalArgumentException("时间数据无效");}}
    private static Instant nullableInstant(String v){return v==null||v.isBlank()?null:instant(v);}
    private static <E extends Enum<E>>E enumValue(Class<E>type,String v){try{return Enum.valueOf(type,v);}catch(RuntimeException e){throw new IllegalArgumentException("状态数据无效");}}
}
