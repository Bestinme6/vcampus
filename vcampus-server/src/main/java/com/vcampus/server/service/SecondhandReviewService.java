package com.vcampus.server.service;

import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.SecondhandReviewStore;
import com.vcampus.server.database.SecondhandReviewStore.MutationResult;
import com.vcampus.server.database.SecondhandReviewStore.Review;
import com.vcampus.server.database.SecondhandReviewStore.ReviewPage;
import com.vcampus.server.database.SecondhandRuleException;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 二手交易双方评价、信誉汇总和管理员评价管理服务。
 *
 * <p>只有完成订单的真实交易双方能够评价，且每个评价方向只能提交一次。</p>
 *
 * @since 1.0
 */
public final class SecondhandReviewService {
    private static final int PAGE_SIZE = 10;
    private final SecondhandReviewStore reviews;
    private final SecondhandReviewImageService images;
    private final SessionManager sessions;

    public SecondhandReviewService(SecondhandReviewStore reviews,
                                   SecondhandReviewImageService images,
                                   SessionManager sessions) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.images = images;
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public ResponseMessage create(RequestMessage request) {
        return handle(request, false, session -> {
            String operation = operation(request.parameters().get("operationId"));
            long orderId = positiveLong(request.parameters().get("orderId"), "订单ID");
            Optional<MutationResult> existing = reviews.findCreated(
                    session.userId(), operation, orderId);
            if (existing.isPresent()) return mutation(request, existing.get());
            int count = imageCount(request);
            List<String> uploadIds = uploadIds(request, count);
            if (count > 0 && images == null) throw new IllegalStateException("图片服务未配置");
            var pending = images == null ? List.<SecondhandReviewStore.PendingImage>of()
                    : images.claim(session.userId(), uploadIds);
            MutationResult result = reviews.create(session.userId(), operation, orderId,
                    integer(request.parameters().get("rating"), "评分"),
                    request.parameters().get("content"), pending);
            if (images != null) images.consume(session.userId(), uploadIds);
            return mutation(request, result);
        });
    }

    public ResponseMessage update(RequestMessage request) {
        return handle(request, false, session -> mutation(request, reviews.update(
                session.userId(), positiveLong(request.parameters().get("reviewId"), "评价ID"),
                integer(request.parameters().get("rating"), "评分"),
                request.parameters().get("content"))));
    }

    public ResponseMessage delete(RequestMessage request) {
        return handle(request, false, session -> ResponseMessage.success(request.requestId(),
                "二手评价已删除", Map.of("changed", Boolean.toString(reviews.delete(
                        session.userId(), positiveLong(request.parameters().get("reviewId"), "评价ID"))))));
    }

    public ResponseMessage setLike(RequestMessage request) {
        return handle(request, false, session -> {
            var result = reviews.setLike(session.userId(),
                    positiveLong(request.parameters().get("reviewId"), "评价ID"),
                    strictBoolean(request.parameters().get("enabled"), "点赞状态"));
            return ResponseMessage.success(request.requestId(), result.enabled() ? "已标记有帮助" : "已取消有帮助",
                    Map.of("reviewId", Long.toString(result.reviewId()),
                            "enabled", Boolean.toString(result.enabled()),
                            "changed", Boolean.toString(result.changed()),
                            "likeCount", Integer.toString(result.likeCount())));
        });
    }

    public ResponseMessage list(RequestMessage request) {
        return handle(request, false, session -> {
            long sellerId = positiveLong(request.parameters().get("sellerId"), "卖家ID");
            ReviewPage page = reviews.listSeller(sellerId, session.userId(), page(request), PAGE_SIZE);
            var summary = reviews.summary(sellerId);
            Map<String, String> data = pageData(page);
            data.put("ratingAverage", summary.average() == null ? "" : summary.average().toPlainString());
            data.put("ratingCount", Integer.toString(summary.count()));
            for (int i=0;i<5;i++) data.put("ratingStar."+(i+1),Integer.toString(summary.starCounts().get(i)));
            encodeRows(data, page, false);
            return ResponseMessage.success(request.requestId(), "二手卖家评价查询成功", data);
        });
    }

    public ResponseMessage searchAdmin(RequestMessage request) {
        return handle(request, true, session -> {
            ShopReviewStatus status = optionalStatus(request.parameters().get("status"));
            ReviewPage page = reviews.searchAdmin(request.parameters().get("keyword"), status,
                    page(request), PAGE_SIZE);
            Map<String, String> data = pageData(page); encodeRows(data, page, true);
            return ResponseMessage.success(request.requestId(), "二手评价管理查询成功", data);
        });
    }

    public ResponseMessage hide(RequestMessage request) { return moderate(request, true); }
    public ResponseMessage restore(RequestMessage request) { return moderate(request, false); }

    private ResponseMessage moderate(RequestMessage request, boolean hidden) {
        return handle(request, true, session -> ResponseMessage.success(request.requestId(),
                hidden ? "二手评价已隐藏" : "二手评价已恢复",
                Map.of("changed", Boolean.toString(reviews.moderate(session.userId(),
                        positiveLong(request.parameters().get("reviewId"), "评价ID"), hidden,
                        request.parameters().get("reason"))))));
    }

    private ResponseMessage handle(RequestMessage request, boolean admin, Work work) {
        Optional<UserSession> found = sessions.find(request.parameters().get("sessionToken"));
        if (found.isEmpty()) return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        if (admin && !ShopAccessPolicy.canManage(found.get().roles())) {
            return ResponseMessage.failure(request.requestId(), "无权管理二手评价");
        }
        try { return work.run(found.get()); }
        catch (SecondhandRuleException | IllegalArgumentException | IllegalStateException error) {
            return ResponseMessage.failure(request.requestId(), error.getMessage());
        } catch (SQLException error) {
            System.err.println("Secondhand review database operation failed: " + error.getMessage());
            return ResponseMessage.failure(request.requestId(), "二手评价暂不可用，请稍后重试");
        }
    }

    private static ResponseMessage mutation(RequestMessage request, MutationResult result) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("row.0", encode(result.review()));
        data.put("duplicate", Boolean.toString(result.duplicate()));
        return ResponseMessage.success(request.requestId(), "二手评价操作成功", data);
    }

    private static Map<String, String> pageData(ReviewPage page) {
        Map<String,String> data=new LinkedHashMap<>();data.put("page",Integer.toString(page.page()));
        data.put("pageSize",Integer.toString(page.pageSize()));data.put("total",Integer.toString(page.total()));
        data.put("rowCount",Integer.toString(page.rows().size()));return data;
    }

    private static void encodeRows(Map<String,String> data,ReviewPage page,boolean admin){
        for(int i=0;i<page.rows().size();i++){
            Review row=page.rows().get(i);data.put("row."+i,encode(row));
            var rowImages=page.images().getOrDefault(row.id(),List.of());
            data.put("row."+i+".imageCount",Integer.toString(rowImages.size()));
            for(int j=0;j<rowImages.size();j++){var image=rowImages.get(j);data.put("row."+i+".image."+j,
                    RowCodec.encode(Long.toString(image.id()),Integer.toString(image.position()),
                            image.mimeType(),Long.toString(image.byteSize()),image.sha256(),
                            Integer.toString(image.width()),Integer.toString(image.height())));}
            if(admin)data.put("row."+i+".status",row.status().name());
        }
    }

    private static String encode(Review row){return RowCodec.encode(Long.toString(row.id()),
            Long.toString(row.orderId()),Long.toString(row.itemId()),Long.toString(row.sellerId()),
            Long.toString(row.authorId()),row.authorName(),Integer.toString(row.rating()),row.content(),
            row.status().name(),Integer.toString(row.likeCount()),Boolean.toString(row.likedByViewer()),
            row.createdAt().toString(),row.updatedAt().toString());}

    private static int imageCount(RequestMessage request){String value=request.parameters().get("imageCount");
        int count=integer(value==null?"0":value,"图片数量");if(count<0||count>3)throw new IllegalArgumentException("评价最多上传3张图片");return count;}
    private static List<String> uploadIds(RequestMessage request,int count){List<String> ids=new ArrayList<>();
        for(int i=0;i<count;i++){String id=request.parameters().get("image."+i);if(id==null||id.isBlank())throw new IllegalArgumentException("图片上传编号无效");ids.add(id);}
        if(new java.util.LinkedHashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("评价图片不能重复");return List.copyOf(ids);}
    private static String operation(String value){try{return UUID.fromString(Objects.requireNonNullElse(value,"").trim()).toString();}catch(IllegalArgumentException error){throw new IllegalArgumentException("业务编号无效");}}
    private static long positiveLong(String value,String label){try{long parsed=Long.parseLong(Objects.requireNonNullElse(value,"").trim());if(parsed<1)throw new NumberFormatException();return parsed;}catch(NumberFormatException error){throw new IllegalArgumentException(label+"无效");}}
    private static int integer(String value,String label){try{return Integer.parseInt(Objects.requireNonNullElse(value,"").trim());}catch(NumberFormatException error){throw new IllegalArgumentException(label+"无效");}}
    private static boolean strictBoolean(String value,String label){if(!"true".equalsIgnoreCase(value)&&!"false".equalsIgnoreCase(value))throw new IllegalArgumentException(label+"无效");return Boolean.parseBoolean(value);}
    private static int page(RequestMessage request){String value=request.parameters().get("page");if(value==null||value.isBlank())return 1;int parsed=integer(value,"页码");if(parsed<1)throw new IllegalArgumentException("页码无效");return parsed;}
    private static ShopReviewStatus optionalStatus(String value){if(value==null||value.isBlank())return null;try{return ShopReviewStatus.valueOf(value.trim());}catch(IllegalArgumentException error){throw new IllegalArgumentException("评价状态无效");}}
    @FunctionalInterface private interface Work{ResponseMessage run(UserSession session)throws SQLException;}
}
