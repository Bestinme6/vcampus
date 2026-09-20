package com.vcampus.server.service;

import com.vcampus.common.model.AccessPolicy;
import com.vcampus.common.model.ModuleCode;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.ShopReviewStore;
import com.vcampus.server.database.ShopReviewStore.AdminReviewPage;
import com.vcampus.server.database.ShopReviewStore.AdminReviewQuery;
import com.vcampus.server.database.ShopReviewStore.CreateReview;
import com.vcampus.server.database.ShopReviewStore.ImagePlan;
import com.vcampus.server.database.ShopReviewStore.ImagePlanItem;
import com.vcampus.server.database.ShopReviewStore.PendingImage;
import com.vcampus.server.database.ShopReviewStore.ReviewPage;
import com.vcampus.server.database.ShopReviewStore.ReviewQuery;
import com.vcampus.server.database.ShopRuleException;
import com.vcampus.server.model.ShopReviewImageRecord;
import com.vcampus.server.model.ShopReviewRecord;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 商品评价创建、查询、互动和管理员审核服务。
 *
 * <p>评价资格来自已完成订单，作者和商品信息由服务端查询，不接受客户端伪造。</p>
 *
 * @since 1.0
 */
public final class ShopReviewService {
    private static final int PAGE_SIZE = 10;
    private static final AuditStore NO_AUDIT = (userId, action, result, address) -> { };
    private static final Set<String> AUDITED_ACTIONS = Set.of(
            Actions.SHOP_REVIEW_CREATE, Actions.SHOP_REVIEW_UPDATE,
            Actions.SHOP_REVIEW_DELETE, Actions.SHOP_REVIEW_LIKE_SET,
            Actions.SHOP_ADMIN_REVIEW_HIDE, Actions.SHOP_ADMIN_REVIEW_RESTORE);
    private final ShopReviewStore reviews;
    private final ShopReviewImageService images;
    private final SessionManager sessions;
    private final AuditStore audit;

    public ShopReviewService(ShopReviewStore reviews, ShopReviewImageService images,
                             SessionManager sessions) {
        this(reviews, images, sessions, NO_AUDIT);
    }

    public ShopReviewService(ShopReviewStore reviews, ShopReviewImageService images,
                             SessionManager sessions, AuditStore audit) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.images = Objects.requireNonNull(images, "images");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public ResponseMessage list(RequestMessage request) {
        return handle(request, false, session -> {
            requireExact(request, Set.of("sessionToken", "productId", "page"), "评价查询参数无效");
            long productId = positiveLong(request.parameters().get("productId"), "商品ID");
            int pageNumber = page(request.parameters().get("page"));
            ReviewPage page = reviews.list(new ReviewQuery(productId, session.userId(), pageNumber, PAGE_SIZE));
            Map<String, String> data = pageData(page.page(), page.pageSize(), page.total(), page.rows().size());
            encodeSummary(data, reviews.summary(productId));
            encodeRows(data, page.rows(), page.images(), false);
            return ResponseMessage.success(request.requestId(), "评价查询成功", data);
        });
    }

    public ResponseMessage create(RequestMessage request) {
        return handle(request, false, session -> {
            int imageCount = imageCount(request);
            Set<String> allowed = new HashSet<>(Set.of("sessionToken", "operationId",
                    "orderItemId", "rating", "content", "imageCount"));
            for (int index = 0; index < imageCount; index++) allowed.add("image." + index);
            requireExact(request, allowed, "评价创建参数无效");
            String operationId = uuid(request.parameters().get("operationId"));
            long orderItemId = positiveLong(request.parameters().get("orderItemId"), "订单商品ID");
            var existing = reviews.findCreated(session.userId(), operationId, orderItemId);
            if (existing.isPresent()) {
                Map<String, String> data = new LinkedHashMap<>();
                data.put("rowCount", "1");
                encodeRow(data, 0, existing.get().review(), List.of(), false);
                data.put("duplicate", "true");
                return ResponseMessage.success(request.requestId(), "评价已提交", data);
            }
            List<String> uploadIds = uploadIds(request, imageCount);
            List<PendingImage> pending = images.claim(session.userId(), uploadIds);
            var result = reviews.create(session.userId(), new CreateReview(operationId,
                    orderItemId,
                    rating(request.parameters().get("rating")),
                    content(request.parameters().get("content")), pending));
            images.consume(session.userId(), uploadIds);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("rowCount", "1");
            encodeRow(data, 0, result.review(), List.of(), false);
            data.put("duplicate", Boolean.toString(result.duplicate()));
            return ResponseMessage.success(request.requestId(),
                    result.duplicate() ? "评价已提交" : "评价发布成功", data);
        });
    }

    public ResponseMessage update(RequestMessage request) {
        return handle(request, false, session -> {
            int imageCount = imageCount(request);
            Set<String> allowed = new HashSet<>(Set.of("sessionToken", "reviewId",
                    "rating", "content", "imageCount"));
            for (int index = 0; index < imageCount; index++) allowed.add("image." + index);
            requireExact(request, allowed, "评价修改参数无效");
            List<PlannedImage> planned = plannedImages(request, imageCount);
            List<String> uploadIds = planned.stream().filter(item -> item.uploadId != null)
                    .map(item -> item.uploadId).toList();
            List<PendingImage> pending = images.claim(session.userId(), uploadIds);
            var pendingIterator = pending.iterator();
            List<ImagePlanItem> items = planned.stream().map(item -> item.existingImageId != null
                    ? new ImagePlanItem(item.existingImageId, null)
                    : new ImagePlanItem(null, pendingIterator.next())).toList();
            var result = reviews.update(session.userId(), new ShopReviewStore.UpdateReview(
                    positiveLong(request.parameters().get("reviewId"), "评价ID"),
                    rating(request.parameters().get("rating")),
                    content(request.parameters().get("content")), new ImagePlan(items)));
            images.consume(session.userId(), uploadIds);
            images.deleteKeys(result.deletedKeys());
            Map<String, String> data = new LinkedHashMap<>();
            data.put("rowCount", "1");
            encodeRow(data, 0, result.review(), List.of(), false);
            return ResponseMessage.success(request.requestId(), "评价已更新", data);
        });
    }

    public ResponseMessage delete(RequestMessage request) {
        return handle(request, false, session -> {
            requireExact(request, Set.of("sessionToken", "reviewId"), "评价删除参数无效");
            boolean changed = reviews.delete(session.userId(),
                    positiveLong(request.parameters().get("reviewId"), "评价ID"));
            return ResponseMessage.success(request.requestId(), changed ? "评价已删除" : "评价状态未变化",
                    Map.of("changed", Boolean.toString(changed)));
        });
    }

    public ResponseMessage setLike(RequestMessage request) {
        return handle(request, false, session -> {
            requireExact(request, Set.of("sessionToken", "reviewId", "enabled"), "评价点赞参数无效");
            var result = reviews.setLike(session.userId(),
                    positiveLong(request.parameters().get("reviewId"), "评价ID"),
                    strictBoolean(request.parameters().get("enabled")));
            return ResponseMessage.success(request.requestId(), result.enabled() ? "已标记有帮助" : "已取消有帮助",
                    Map.of("reviewId", Long.toString(result.reviewId()),
                            "enabled", Boolean.toString(result.enabled()),
                            "changed", Boolean.toString(result.changed()),
                            "likeCount", Integer.toString(result.likeCount())));
        });
    }

    public ResponseMessage searchAdmin(RequestMessage request) {
        return handle(request, true, session -> {
            requireExact(request, Set.of("sessionToken", "keyword", "status", "page"), "评价管理查询参数无效");
            String statusValue = request.parameters().get("status");
            ShopReviewStatus status = statusValue == null || statusValue.isBlank()
                    ? null : ShopReviewStatus.valueOf(statusValue.trim().toUpperCase(java.util.Locale.ROOT));
            AdminReviewPage page = reviews.searchAdmin(new AdminReviewQuery(
                    request.parameters().get("keyword"), status,
                    page(request.parameters().get("page")), PAGE_SIZE));
            Map<String, String> data = pageData(page.page(), page.pageSize(), page.total(), page.rows().size());
            encodeRows(data, page.rows(), page.images(), true);
            return ResponseMessage.success(request.requestId(), "评价管理查询成功", data);
        });
    }

    public ResponseMessage hide(RequestMessage request) {
        return moderate(request, true);
    }

    public ResponseMessage restore(RequestMessage request) {
        return moderate(request, false);
    }

    private ResponseMessage moderate(RequestMessage request, boolean hide) {
        return handle(request, true, session -> {
            requireExact(request, Set.of("sessionToken", "reviewId", "reason"), "评价审核参数无效");
            String reason = required(request.parameters().get("reason"), "审核原因");
            if (reason.length() < 2 || reason.length() > 255) {
                throw new IllegalArgumentException("审核原因需为2到255字");
            }
            long reviewId = positiveLong(request.parameters().get("reviewId"), "评价ID");
            boolean changed = hide ? reviews.hide(session.userId(), reviewId, reason)
                    : reviews.restore(session.userId(), reviewId, reason);
            return ResponseMessage.success(request.requestId(), changed
                    ? (hide ? "评价已隐藏" : "评价已恢复") : "评价状态未变化",
                    Map.of("changed", Boolean.toString(changed)));
        });
    }

    private ResponseMessage handle(RequestMessage request, boolean administrative, Work work) {
        Optional<UserSession> found = sessions.find(request.parameters().get("sessionToken"));
        if (found.isEmpty()) return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        UserSession actor = found.get();
        boolean allowed = administrative ? ShopAccessPolicy.canManage(actor.roles())
                : AccessPolicy.canAccess(ModuleCode.SHOP, actor.roles());
        if (!allowed) return audited(actor, request, "DENIED", ResponseMessage.failure(
                request.requestId(), administrative ? "无权执行商店评价管理操作" : "无权使用校园商店"));
        try {
            return audited(actor, request, "SUCCESS", work.run(actor));
        } catch (ShopRuleException failure) {
            return audited(actor, request, "CONFLICT",
                    ResponseMessage.failure(request.requestId(), failure.getMessage()));
        } catch (IllegalArgumentException failure) {
            return audited(actor, request, "INVALID",
                    ResponseMessage.failure(request.requestId(), failure.getMessage()));
        } catch (SQLException failure) {
            System.err.println("Shop review database operation failed: " + failure.getMessage());
            return audited(actor, request, "DATABASE_ERROR",
                    ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试"));
        }
    }

    private ResponseMessage audited(UserSession actor, RequestMessage request,
                                    String result, ResponseMessage response) {
        if (AUDITED_ACTIONS.contains(request.action())) {
            audit.record(actor.userId(), request.action(), result, null);
        }
        return response;
    }

    private void encodeSummary(Map<String, String> data, ShopReviewStore.ReviewSummary summary) {
        data.put("ratingAverage", summary.average() == null ? "" : summary.average().toPlainString());
        data.put("ratingCount", Integer.toString(summary.total()));
        for (int index = 0; index < 5; index++) {
            data.put("ratingStar." + (index + 1), Integer.toString(summary.starCounts().get(index)));
        }
    }

    private void encodeRows(Map<String, String> data, List<ShopReviewRecord> rows,
                            Map<Long, List<ShopReviewImageRecord>> imageMap, boolean admin) {
        for (int index = 0; index < rows.size(); index++) {
            ShopReviewRecord row = rows.get(index);
            encodeRow(data, index, row, imageMap.getOrDefault(row.id(), List.of()), admin);
        }
    }

    private void encodeRow(Map<String, String> data, int index, ShopReviewRecord row,
                           List<ShopReviewImageRecord> rowImages, boolean admin) {
        data.put("row." + index, RowCodec.encode(Long.toString(row.id()),
                Long.toString(row.productId()), Long.toString(row.orderItemId()),
                row.authorDisplayName(), Integer.toString(row.rating()), row.content(),
                Boolean.toString(row.repeatPurchase()), Integer.toString(row.likeCount()),
                Boolean.toString(row.likedByViewer()), row.createdAt().toString(), row.updatedAt().toString()));
        data.put("row." + index + ".imageCount", Integer.toString(rowImages.size()));
        for (int imageIndex = 0; imageIndex < rowImages.size(); imageIndex++) {
            ShopReviewImageRecord image = rowImages.get(imageIndex);
            data.put("row." + index + ".image." + imageIndex, RowCodec.encode(
                    Long.toString(image.id()), Integer.toString(image.position()), image.mimeType(),
                    Long.toString(image.byteSize()), image.sha256(), Integer.toString(image.width()),
                    Integer.toString(image.height())));
        }
        if (admin) data.put("row." + index + ".status", row.status().name());
    }

    private Map<String, String> pageData(int page, int pageSize, int total, int rowCount) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", Integer.toString(page));
        data.put("pageSize", Integer.toString(pageSize));
        data.put("total", Integer.toString(total));
        data.put("rowCount", Integer.toString(rowCount));
        return data;
    }

    private int imageCount(RequestMessage request) {
        int count = integer(request.parameters().get("imageCount"), "图片数量");
        if (count < 0 || count > 3) throw new IllegalArgumentException("评价最多上传3张图片");
        return count;
    }

    private List<String> uploadIds(RequestMessage request, int count) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ids.add(required(request.parameters().get("image." + index), "图片上传编号"));
        }
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("评价图片不能重复");
        return List.copyOf(ids);
    }

    private List<PlannedImage> plannedImages(RequestMessage request, int count) {
        List<PlannedImage> result = new ArrayList<>();
        Set<Long> existing = new HashSet<>();
        Set<String> uploads = new HashSet<>();
        for (int index = 0; index < count; index++) {
            List<String> fields = RowCodec.decode(required(
                    request.parameters().get("image." + index), "图片计划"));
            if (fields.size() != 2) throw new IllegalArgumentException("图片计划无效");
            if (fields.get(0).equals("EXISTING")) {
                long id = positiveLong(fields.get(1), "评价图片ID");
                if (!existing.add(id)) throw new IllegalArgumentException("评价图片不能重复");
                result.add(new PlannedImage(id, null));
            } else if (fields.get(0).equals("UPLOAD")) {
                String id = required(fields.get(1), "图片上传编号");
                if (!uploads.add(id)) throw new IllegalArgumentException("评价图片不能重复");
                result.add(new PlannedImage(null, id));
            } else {
                throw new IllegalArgumentException("图片计划无效");
            }
        }
        return List.copyOf(result);
    }

    private void requireExact(RequestMessage request, Set<String> expected, String message) {
        if (!request.parameters().keySet().equals(expected)) throw new IllegalArgumentException(message);
    }

    private String uuid(String value) {
        String result = required(value, "操作编号");
        try {
            UUID.fromString(result);
            return result;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("操作编号无效");
        }
    }

    private int rating(String value) {
        int rating = integer(value, "评分");
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("评分必须为1到5星");
        return rating;
    }

    private String content(String value) {
        String content = required(value, "评价内容");
        if (content.length() < 5 || content.length() > 1000) {
            throw new IllegalArgumentException("评价内容需为5到1000字");
        }
        return content;
    }

    private String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private long positiveLong(String value, String label) {
        try {
            long result = Long.parseLong(value == null ? "" : value);
            if (result < 1) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    private int integer(String value, String label) {
        try {
            return Integer.parseInt(value == null ? "" : value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    private int page(String value) {
        int result = integer(value, "页码");
        if (result < 1) throw new IllegalArgumentException("页码无效");
        return result;
    }

    private boolean strictBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("点赞状态无效");
        }
        return Boolean.parseBoolean(value);
    }

    private record PlannedImage(Long existingImageId, String uploadId) {
    }

    @FunctionalInterface
    private interface Work {
        ResponseMessage run(UserSession session) throws SQLException;
    }
}
