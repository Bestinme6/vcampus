package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.client.fx.shop.ShopData.ProductDetail;
import com.vcampus.client.fx.shop.ShopData.ProductPage;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import com.vcampus.common.model.ShippingAddressChoice;

/** Session-scoped JavaFX shop coordinator. Public lifecycle methods are FX-thread confined. */
public final class ShopController implements AutoCloseable, ShopView.Listener {
    private final ShopGateway gateway;
    private final Set<UserRole> roles;
    private final Executor executor;
    private final ShopImageCache cache;
    private final ShopReviewImageCache reviewCache;
    private final SecondhandController secondhand;
    private final Runnable back;
    private final Runnable unreadRefresh;
    private final ShopView view;
    private final ShopCartView cartView;
    private final ShopCheckoutView checkoutView;
    private final ShopOrdersView ordersView;
    private final ShopOrderDetailView orderDetailView;
    private final ShopAddressView addressView;
    private final ShopAdminView adminView;
    private final ShopProductEditorView editorView;
    private final ShopReviewEditorView reviewEditorView;
    private final ShopReviewAdminView reviewAdminView;
    private final ShopImageUploader uploader;
    private final ShopReviewImageUploader reviewUploader;
    private ShopData.Cart currentCart;
    private ShopData.OrderDetail currentOrder;
    private long reviewOriginOrderId;
    private long reviewEditorProductId;
    private ShopReviewAdminView.Query currentReviewAdminQuery = new ShopReviewAdminView.Query("", null, 1);
    private long generation;
    private boolean active = true;
    private boolean closed;

    public ShopController(ShopGateway gateway, Set<UserRole> roles, Executor executor,
                          ShopImageCache cache, Runnable back, Runnable unreadRefresh) {
        this(gateway, null, 0L, roles, executor, cache, back, unreadRefresh);
    }

    public ShopController(ShopGateway gateway, SecondhandGateway secondhandGateway, long userId,
                          Set<UserRole> roles, Executor executor, ShopImageCache cache,
                          Runnable back, Runnable unreadRefresh) {
        requireFx();
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.roles = Set.copyOf(roles);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.reviewCache = new ShopReviewImageCache(gateway, executor);
        this.back = Objects.requireNonNull(back, "back");
        this.unreadRefresh = Objects.requireNonNull(unreadRefresh, "unreadRefresh");
        this.secondhand = secondhandGateway == null ? null
                : new SecondhandController(secondhandGateway, gateway, userId, this.roles,
                        executor, unreadRefresh);
        this.view = new ShopView(this.roles, this);
        this.cartView = new ShopCartView(new ShopCartView.Listener() {
            @Override public void quantity(long productId, int quantity) { updateCartQuantity(productId, quantity); }
            @Override public void remove(long productId) { removeCartItem(productId); }
            @Override public void checkout(Set<Long> selectedProductIds) { openCartCheckout(selectedProductIds); }
        });
        this.checkoutView = new ShopCheckoutView(this::confirmCheckout, this::returnFromCheckout);
        this.ordersView = new ShopOrdersView(ShopController.this::openOrder, ShopController.this::openOrders);
        this.orderDetailView = new ShopOrderDetailView(new ShopOrderDetailView.Listener() {
            @Override public void back() { openOrders(1); }
            @Override public void cancel(long orderId) { cancelOrder(orderId); }
            @Override public void confirm(long orderId) { confirmOrder(orderId); }
            @Override public void review(long orderId, long orderItemId, Long reviewId) {
                openOrderReview(orderId, orderItemId, reviewId);
            }
        });
        this.addressView = new ShopAddressView(new ShopAddressView.Listener() {
            @Override public void back() { search("", null, ShopProductSort.NEWEST, 1); }
            @Override public void save(Integer replaceSlot,
                                       com.vcampus.common.model.ShippingAddressValue value) {
                saveAddress(replaceSlot, value);
            }
            @Override public void delete(int slot) { deleteAddress(slot); }
        });
        this.adminView = new ShopAdminView(new ShopAdminView.Listener() {
            @Override public void searchProducts(ShopAdminView.ProductQuery query) { openAdminProducts(query); }
            @Override public void editProduct(long productId) { openAdminProduct(productId); }
            @Override public void newProduct() { openNewProduct(); }
            @Override public void setEnabled(long productId, boolean enabled) { setAdminProductEnabled(productId, enabled); }
            @Override public void adjustInventory(long productId, int delta, String reason) { adjustAdminInventory(productId, delta, reason); }
            @Override public void searchOrders(ShopAdminView.OrderQuery query) { openAdminOrders(query); }
            @Override public void ship(long orderId) { shipAdminOrder(orderId); }
            @Override public void searchReviews(ShopReviewAdminView.Query query) { openAdminReviews(query); }
        });
        this.editorView = new ShopProductEditorView(this::saveAdminProduct,
                () -> openAdminProducts(defaultProductQuery()), this::previewUpload);
        this.uploader = new ShopImageUploader(gateway, executor);
        this.reviewUploader = new ShopReviewImageUploader(gateway, executor);
        this.reviewEditorView = new ShopReviewEditorView(new ShopReviewEditorView.Listener() {
            @Override public void save(ShopReviewEditorView.Submission submission) { saveReview(submission); }
            @Override public void delete(long reviewId) { deleteReview(reviewId); }
            @Override public void cancel() {
                if (reviewOriginOrderId > 0) openOrder(reviewOriginOrderId);
                else if (reviewEditorProductId > 0) openProduct(reviewEditorProductId);
            }
            @Override public void selectImage(java.nio.file.Path file) { selectReviewImage(file); }
        });
        this.reviewAdminView = new ShopReviewAdminView(new ShopReviewAdminView.Listener() {
            @Override public void search(ShopReviewAdminView.Query query) { openAdminReviews(query); }
            @Override public void hide(long reviewId, String reason) { moderateReview(reviewId, reason, true); }
            @Override public void restore(long reviewId, String reason) { moderateReview(reviewId, reason, false); }
        });
    }

    public Parent view() { requireFx(); return view; }

    public void open(String route) {
        requireFx();
        if (closed) return;
        active = true;
        if (route != null && route.startsWith("admin/") && !ShopAccessPolicy.canManage(roles)) {
            throw new IllegalStateException("当前账号没有商店管理权限");
        }
        if (route == null || route.equals("shop") || route.equals("catalog")) {
            search("", null, ShopProductSort.NEWEST, 1);
        } else if (route.startsWith("product/")) {
            try { openProduct(Long.parseLong(route.substring("product/".length()))); }
            catch (NumberFormatException error) { view.status("商品地址无效", true); }
        } else if (route.equals("cart")) {
            openCart();
        } else if (route.equals("orders")) {
            openOrders(1);
        } else if (route.equals("addresses")) {
            openAddresses();
        } else if (route.equals("used") || route.startsWith("used-order/")) {
            used(route);
        } else if (route.startsWith("order/")) {
            try { openOrder(Long.parseLong(route.substring("order/".length()))); }
            catch (NumberFormatException error) { view.status("订单地址无效", true); }
        } else if (route.startsWith("checkout/direct/")) {
            String[] values = route.substring("checkout/direct/".length()).split("/");
            try { openDirectCheckout(Long.parseLong(values[0]), Integer.parseInt(values[1])); }
            catch (RuntimeException error) { view.status("立即购买地址无效", true); }
        } else if (route.equals("checkout/cart")) {
            if (currentCart == null) openCart(); else openCartCheckout(cartView.selectedProductIds());
        } else if (route.equals("admin/products")) {
            openAdminProducts(defaultProductQuery());
        } else if (route.equals("admin/product/new")) {
            openNewProduct();
        } else if (route.startsWith("admin/product/")) {
            try { openAdminProduct(Long.parseLong(route.substring("admin/product/".length()))); }
            catch (NumberFormatException error) { view.status("商品管理地址无效", true); }
        } else if (route.equals("admin/orders")) {
            openAdminOrders(new ShopAdminView.OrderQuery("", null, 1));
        } else if (route.equals("admin/reviews")) {
            openAdminReviews(new ShopReviewAdminView.Query("", null, 1));
        } else {
            view.status("没有找到要打开的商店页面", true);
        }
    }

    @Override public void search(String keyword, ShopCategory category, ShopProductSort sort, int page) {
        requireFx();
        if (closed || !active || !confirmLeaveReviewEditor()) return;
        view.showCatalog();
        view.catalog().showLoading();
        long ticket = ++generation;
        request(ticket, () -> gateway.search(keyword, category, sort, page), result -> {
            view.catalog().show(result);
            view.status("", false);
            loadCatalogImages(result, ticket);
        }, error -> view.catalog().showFailure(message(error),
                () -> search(keyword, category, sort, page)));
    }

    @Override public void product(long productId) { openProduct(productId); }

    public void openProduct(long productId) {
        requireFx();
        if (closed || !active || !confirmLeaveReviewEditor()) return;
        if (productId < 1) { view.status("商品编号无效", true); return; }
        long ticket = ++generation;
        view.busy(true);
        view.status("正在打开商品…", false);
        request(ticket, () -> gateway.product(productId), detail -> {
            view.busy(false);
            view.detail().show(detail);
            view.showDetail();
            view.status("", false);
            loadDetailImages(detail, ticket);
            loadReviews(productId, 1, ticket);
        }, error -> {
            view.busy(false);
            view.showCatalog();
            view.catalog().showFailure(message(error), () -> openProduct(productId));
            view.status("", false);
        });
    }

    @Override public void addToCart(long productId, int quantity) {
        requireFx();
        if (closed || !active) return;
        long ticket = ++generation;
        view.busy(true);
        request(ticket, () -> {
            var cart = gateway.cart();
            int existing = cart.items().stream().filter(item -> item.productId() == productId)
                    .mapToInt(ShopData.CartItem::quantity).findFirst().orElse(0);
            return gateway.setCartQuantity(productId, Math.addExact(existing, quantity));
        }, cart -> {
            view.busy(false);
            view.status("已加入购物车 · 当前 " + cart.items().size() + " 种商品", false);
            unreadRefresh.run();
        }, error -> { view.busy(false); view.status(message(error), true); });
    }

    @Override public void buyNow(ShopDetailView.BuyNowSelection selection) {
        requireFx();
        if (closed || !active || !confirmLeaveReviewEditor()) return;
        openDirectCheckout(selection.productId(), selection.quantity());
    }

    @Override public void cart() { openCart(); }
    @Override public void orders() { openOrders(1); }
    @Override public void addresses() { openAddresses(); }
    @Override public void used() { used("used"); }
    @Override public void admin() {
        requireFx(); requireManager(); openAdminProducts(defaultProductQuery());
    }

    @Override public void reviewPage(long productId, int page) {
        requireFx();
        if (closed || !active || productId < 1 || page < 1) return;
        long ticket = ++generation;
        view.detail().reviews().showLoading(productId);
        loadReviews(productId, page, ticket);
    }

    @Override public void reviewLike(long reviewId, boolean enabled) {
        requireFx();
        if (closed || !active || reviewId < 1) return;
        long ticket = generation;
        view.detail().reviews().setLikeBusy(reviewId, true);
        request(ticket, () -> gateway.setReviewLike(reviewId, enabled), result -> {
            view.detail().reviews().applyLike(result);
            view.status(result.changed() ? "感谢你的反馈" : "评价点赞状态未变化", false);
        }, error -> {
            view.detail().reviews().setLikeBusy(reviewId, false);
            view.status(message(error), true);
        });
    }

    @Override public void reviewImage(ShopData.ReviewImageRef image) {
        requireFx();
        if (image == null) return;
        long ticket = generation;
        view.status("正在读取评价大图…", false);
        reviewCache.load(image, ShopData.ImageVariant.DETAIL)
                .thenApplyAsync(ShopController::decodeReviewImage, executor)
                .whenComplete((loaded, error) -> Platform.runLater(() -> {
                    if (!valid(ticket)) return;
                    if (error != null) view.status(message(error), true);
                    else {
                        view.detail().reviews().showDetail(image, loaded);
                        view.status("", false);
                    }
                }));
    }

    @Override public void back() {
        requireFx();
        if (!closed && confirmLeaveReviewEditor()) back.run();
    }

    public boolean canLeave() {
        requireFx();
        return closed || confirmLeaveReviewEditor();
    }

    public void deactivate() {
        requireFx();
        if (closed) return;
        active = false;
        ++generation;
        view.busy(false);
        if (secondhand != null) secondhand.deactivate();
    }

    @Override public void close() {
        requireFx();
        if (closed) return;
        active = false;
        closed = true;
        ++generation;
        cache.close();
        reviewCache.close();
        if (secondhand != null) secondhand.close();
    }

    ShopCatalogView catalogView() { requireFx(); return view.catalog(); }

    private void used(String route) {
        requireFx();
        if (closed || !active || !confirmLeaveReviewEditor()) return;
        if (secondhand == null) {
            view.status("二手市场暂不可用", true);
            return;
        }
        ++generation;
        view.page(secondhand.view());
        view.status("", false);
        secondhand.open(route);
    }

    private void openCart() {
        requireFx();
        if (closed || !active || !confirmLeaveReviewEditor()) return;
        long ticket = ++generation;
        view.page(cartView); view.status("正在读取购物车…", false);
        request(ticket, gateway::cart, cart -> {
            currentCart = cart; cartView.show(cart); view.status("", false);
        }, error -> view.status(message(error), true));
    }

    private void updateCartQuantity(long productId, int quantity) {
        requireFx();
        long ticket = ++generation; view.busy(true);
        request(ticket, () -> gateway.setCartQuantity(productId, quantity), cart -> {
            currentCart = cart; cartView.show(cart); view.busy(false); view.status("购物车数量已更新", false);
        }, error -> { view.busy(false); view.status(message(error), true); });
    }

    private void removeCartItem(long productId) {
        requireFx();
        long ticket = ++generation; view.busy(true);
        request(ticket, () -> gateway.removeCartItem(productId), cart -> {
            currentCart = cart; cartView.show(cart); view.busy(false); view.status("商品已移出购物车", false);
        }, error -> { view.busy(false); view.status(message(error), true); });
    }

    private void openDirectCheckout(long productId, int quantity) {
        requireFx();
        if (productId < 1 || quantity < 1 || quantity > 999) { view.status("购买数量无效", true); return; }
        long ticket = ++generation; view.status("正在准备订单确认…", false);
        request(ticket, () -> new DirectCheckoutData(gateway.product(productId),
                gateway.bankBalance(), gateway.addresses()), data -> {
            var product = data.detail().product();
            var line = new ShopCheckoutView.CheckoutLine(product.id(), product.name(), product.price(),
                    quantity, product.stock(), product.enabled());
            showCheckout(new ShopCheckoutView.CheckoutDraft(ShopCheckoutView.Source.DIRECT,
                    List.of(line), line.subtotal(), data.balance(),
                    UUID.randomUUID().toString()), data.addresses());
        }, error -> view.status(message(error), true));
    }

    private void openCartCheckout(Set<Long> selectedIds) {
        requireFx();
        if (currentCart == null || selectedIds == null || selectedIds.isEmpty()) {
            view.status("请先勾选可结算商品", true); return;
        }
        List<ShopCheckoutView.CheckoutLine> lines = currentCart.items().stream()
                .filter(item -> selectedIds.contains(item.productId()))
                .map(item -> new ShopCheckoutView.CheckoutLine(item.productId(), item.name(), item.unitPrice(),
                        item.quantity(), item.stock(), item.enabled())).toList();
        if (lines.isEmpty() || lines.stream().anyMatch(line -> !line.eligible())) {
            view.status("所选商品状态已变化，请刷新购物车", true); return;
        }
        long ticket = ++generation;
        request(ticket, () -> new CartCheckoutData(gateway.bankBalance(), gateway.addresses()), data -> {
            var total = lines.stream().map(ShopCheckoutView.CheckoutLine::subtotal)
                    .reduce(new java.math.BigDecimal("0.00"), java.math.BigDecimal::add);
            showCheckout(new ShopCheckoutView.CheckoutDraft(ShopCheckoutView.Source.CART, lines,
                    total, data.balance(), UUID.randomUUID().toString()), data.addresses());
        }, error -> view.status(message(error), true));
    }

    private void showCheckout(ShopCheckoutView.CheckoutDraft draft,
                              List<com.vcampus.common.model.ShopAddress> addresses) {
        checkoutView.show(draft, addresses); view.page(checkoutView); view.status("", false);
    }

    private void confirmCheckout(ShopCheckoutView.CheckoutSubmission submission) {
        requireFx();
        long ticket = ++generation;
        checkoutView.busy(true);
        request(ticket, () -> {
            ShippingAddressChoice address = submission.address();
            if (submission.saveAddress()) {
                var saved = gateway.saveAddress(submission.replaceSlot(), address.inline());
                address = new ShippingAddressChoice(saved.slot(), saved.version(), null);
            }
            ShopCheckoutView.CheckoutDraft draft = submission.draft();
            return draft.source() == ShopCheckoutView.Source.DIRECT
                    ? gateway.buyNow(draft.items().getFirst().productId(),
                            draft.items().getFirst().quantity(), draft.operationId(), address)
                    : gateway.checkout(draft.items().stream()
                            .map(ShopCheckoutView.CheckoutLine::productId)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                            draft.operationId(), address);
        }, receipt -> {
            unreadRefresh.run(); currentCart = null; openOrder(receipt.orderId());
        }, error -> {
            checkoutView.failure(message(error));
        });
    }

    private void openAddresses() {
        requireFx();
        if (closed || !active || !confirmLeaveReviewEditor()) return;
        long ticket = ++generation;
        view.page(addressView);
        addressView.busy(true);
        view.status("正在读取收货地址…", false);
        request(ticket, gateway::addresses, rows -> {
            addressView.busy(false);
            addressView.show(rows);
            view.status("", false);
        }, error -> addressView.failure(message(error)));
    }

    private void saveAddress(Integer replaceSlot,
                             com.vcampus.common.model.ShippingAddressValue value) {
        requireFx();
        long ticket = ++generation;
        addressView.busy(true);
        request(ticket, () -> gateway.saveAddress(replaceSlot, value), ignored -> {
            view.status("收货地址已保存", false);
            openAddresses();
        }, error -> addressView.failure(message(error)));
    }

    private void deleteAddress(int slot) {
        requireFx();
        long ticket = ++generation;
        addressView.busy(true);
        request(ticket, () -> gateway.deleteAddress(slot), ignored -> {
            view.status("收货地址已删除", false);
            openAddresses();
        }, error -> addressView.failure(message(error)));
    }

    private void returnFromCheckout() {
        requireFx();
        if (checkoutView.draft() != null && checkoutView.draft().source() == ShopCheckoutView.Source.CART) openCart();
        else if (checkoutView.draft() != null) openProduct(checkoutView.draft().items().getFirst().productId());
        else search("", null, ShopProductSort.NEWEST, 1);
    }

    private void openOrders(int page) {
        requireFx();
        if (!confirmLeaveReviewEditor()) return;
        long ticket = ++generation; view.page(ordersView); view.status("正在读取订单…", false);
        request(ticket, () -> gateway.orders(null, page), orders -> {
            ordersView.show(orders); view.status("", false);
        }, error -> view.status(message(error), true));
    }

    private void openOrder(long orderId) {
        requireFx();
        if (!confirmLeaveReviewEditor()) return;
        if (orderId < 1) { view.status("订单编号无效", true); return; }
        long ticket = ++generation; view.status("正在读取订单详情…", false);
        request(ticket, () -> gateway.order(orderId), detail -> {
            currentOrder = detail;
            orderDetailView.show(detail); view.page(orderDetailView); view.status("", false);
        }, error -> view.status(message(error), true));
    }

    private void cancelOrder(long orderId) { mutateOrder(() -> gateway.cancelOrder(orderId), orderId); }
    private void confirmOrder(long orderId) {
        requireFx();
        long ticket = ++generation;
        view.busy(true);
        request(ticket, () -> {
            gateway.confirmOrder(orderId);
            return gateway.order(orderId);
        }, detail -> {
            view.busy(false);
            unreadRefresh.run();
            currentOrder = detail;
            orderDetailView.show(detail);
            view.page(orderDetailView);
            view.status("已确认收货", false);
            offerImmediateReview(detail);
        }, error -> { view.busy(false); view.status(message(error), true); });
    }

    private void offerImmediateReview(ShopData.OrderDetail detail) {
        ShopData.OrderItem eligible = detail.items().stream().filter(ShopData.OrderItem::reviewable)
                .findFirst().orElse(null);
        if (eligible == null) return;
        ButtonType now = new ButtonType("立即评价", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("稍后评价", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert choice = new Alert(Alert.AlertType.CONFIRMATION,
                "收货成功。现在分享这件商品的使用体验吗？", now, later);
        choice.setTitle("确认收货");
        choice.setHeaderText("是否立即评价");
        if (view.getScene() != null) choice.initOwner(view.getScene().getWindow());
        choice.showAndWait().filter(now::equals).ifPresent(ignored ->
                openOrderReview(detail.order().id(), eligible.id(), null));
    }

    private void mutateOrder(Callable<ShopData.CheckoutReceipt> mutation, long orderId) {
        requireFx();
        long ticket = ++generation; view.busy(true);
        request(ticket, mutation, receipt -> { view.busy(false); unreadRefresh.run(); openOrder(orderId); },
                error -> { view.busy(false); view.status(message(error), true); });
    }

    private void openOrderReview(long orderId, long orderItemId, Long reviewId) {
        requireFx();
        if (currentOrder == null || currentOrder.order().id() != orderId) {
            view.status("订单信息已变化，请重新打开订单", true);
            openOrder(orderId);
            return;
        }
        ShopData.OrderItem item = currentOrder.items().stream()
                .filter(value -> value.id() == orderItemId).findFirst().orElse(null);
        if (item == null) { view.status("订单商品不存在", true); return; }
        reviewOriginOrderId = orderId;
        reviewEditorProductId = item.productId();
        if (reviewId == null) {
            reviewEditorView.showCreate(item.id(), item.productId(), item.nameSnapshot());
            view.page(reviewEditorView);
            view.status("", false);
            return;
        }
        long ticket = ++generation;
        view.status("正在读取你的评价…", false);
        findReview(item, reviewId, 1, ticket);
    }

    private void findReview(ShopData.OrderItem item, long reviewId, int page, long ticket) {
        request(ticket, () -> gateway.reviews(item.productId(), page), result -> {
            ShopData.Review found = result.rows().stream().filter(row -> row.id() == reviewId)
                    .findFirst().orElse(null);
            if (found != null) {
                reviewEditorView.showEdit(found, item.nameSnapshot());
                view.page(reviewEditorView); view.status("", false);
                loadReviewEditorImages(found, ticket);
            } else if ((long) result.page() * result.pageSize() < result.total()) {
                findReview(item, reviewId, page + 1, ticket);
            } else {
                view.status("该评价当前不可编辑，已为你打开商品评价区", true);
                openProduct(item.productId());
            }
        }, error -> view.status(message(error), true));
    }

    private void saveReview(ShopReviewEditorView.Submission submission) {
        requireFx();
        long ticket = ++generation;
        reviewEditorView.busy(true);
        view.status("正在提交评价…", false);
        uploadReviewDraft(submission, 0, ticket);
    }

    private void selectReviewImage(java.nio.file.Path file) {
        requireFx();
        long ticket = generation;
        reviewEditorView.busy(true);
        view.status("正在检查评价图片…", false);
        try {
            executor.execute(() -> {
                try {
                    ShopReviewImageDraft.FileInfo info = ShopReviewImageDraft.inspect(file);
                    Platform.runLater(() -> {
                        if (!valid(ticket) || !view.isPage(reviewEditorView)) return;
                        reviewEditorView.addImage(info);
                        reviewEditorView.busy(false);
                        view.status("已添加 " + info.displayName(), false);
                    });
                } catch (Throwable error) {
                    Platform.runLater(() -> {
                        if (!valid(ticket) || !view.isPage(reviewEditorView)) return;
                        reviewEditorView.busy(false);
                        view.status(message(error), true);
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            reviewEditorView.busy(false);
            view.status(message(error), true);
        }
    }

    private void uploadReviewDraft(ShopReviewEditorView.Submission submission, int index, long ticket) {
        if (!valid(ticket)) return;
        List<ShopReviewImageDraft.DraftImage> images = submission.draft().images();
        if (index >= images.size()) {
            request(ticket, () -> persistReview(submission), review -> {
                reviewEditorView.busy(false);
                reviewEditorView.markSaved();
                reviewCache.clearMemory();
                view.status("评价已保存", false);
                openProduct(review.productId());
            }, error -> { reviewEditorView.busy(false); view.status(message(error), true); });
            return;
        }
        ShopReviewImageDraft.DraftImage image = images.get(index);
        if (image.uploadId() != null) {
            uploadReviewDraft(submission, index + 1, ticket);
            return;
        }
        view.status("正在上传评价图片 " + (index + 1) + " / " + images.size(), false);
        reviewUploader.upload(image.file(), ignored -> { }).whenComplete((uploadId, error) ->
                Platform.runLater(() -> {
                    if (!valid(ticket)) return;
                    if (error != null) {
                        reviewEditorView.busy(false); view.status(message(error), true); return;
                    }
                    submission.draft().markUploaded(image.key(), uploadId);
                    uploadReviewDraft(submission, index + 1, ticket);
                }));
    }

    private ShopData.Review persistReview(ShopReviewEditorView.Submission submission) throws IOException {
        if (submission.reviewId() == null) {
            List<String> uploadIds = submission.imageOrder().stream().map(selection ->
                    draftImage(submission.draft(), selection.draftKey()).uploadId()).toList();
            return gateway.createReview(new ShopData.ReviewDraft(submission.operationId(),
                    submission.orderItemId(), submission.rating(), submission.content(),
                    uploadIds));
        }
        List<ShopData.ReviewImagePlanItem> plan = new java.util.ArrayList<>();
        submission.imageOrder().forEach(selection -> {
            if (selection.existingImageId() != null) {
                plan.add(new ShopData.ReviewImagePlanItem(selection.existingImageId(), null));
            } else {
                plan.add(new ShopData.ReviewImagePlanItem(null,
                        draftImage(submission.draft(), selection.draftKey()).uploadId()));
            }
        });
        return gateway.updateReview(new ShopData.ReviewUpdate(submission.reviewId(), submission.rating(),
                submission.content(), plan));
    }

    private ShopReviewImageDraft.DraftImage draftImage(ShopReviewImageDraft draft, String key) {
        return draft.images().stream().filter(image -> image.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("评价图片草稿不存在"));
    }

    private void deleteReview(long reviewId) {
        requireFx();
        long ticket = ++generation;
        reviewEditorView.busy(true);
        request(ticket, () -> gateway.deleteReview(reviewId), ignored -> {
            reviewEditorView.busy(false);
            reviewEditorView.markSaved();
            view.status("评价已删除", false);
            openProduct(reviewEditorProductId);
        }, error -> { reviewEditorView.busy(false); view.status(message(error), true); });
    }

    private record DirectCheckoutData(ProductDetail detail, java.math.BigDecimal balance,
                                      List<com.vcampus.common.model.ShopAddress> addresses) { }
    private record CartCheckoutData(java.math.BigDecimal balance,
                                    List<com.vcampus.common.model.ShopAddress> addresses) { }

    private ShopAdminView.ProductQuery defaultProductQuery() {
        return new ShopAdminView.ProductQuery("", null, true, ShopProductSort.NEWEST, 1);
    }

    private void openAdminProducts(ShopAdminView.ProductQuery query) {
        requireFx(); requireManager();
        if (!confirmLeaveReviewEditor()) return;
        long ticket = ++generation; view.page(adminView); view.status("正在读取商品管理数据…", false);
        request(ticket, () -> gateway.adminSearchProducts(query.keyword(), query.category(), query.enabled(),
                query.sort(), query.page()), page -> { adminView.showProducts(page); view.status("", false); },
                error -> view.status(message(error), true));
    }

    private void openAdminOrders(ShopAdminView.OrderQuery query) {
        requireFx(); requireManager();
        if (!confirmLeaveReviewEditor()) return;
        long ticket = ++generation; view.page(adminView); view.status("正在读取待处理订单…", false);
        request(ticket, () -> gateway.adminOrders(query.keyword(), query.status(), query.page()),
                page -> { adminView.showOrders(page); view.status("", false); },
                error -> view.status(message(error), true));
    }

    private void openAdminReviews(ShopReviewAdminView.Query query) {
        requireFx(); requireManager();
        if (!confirmLeaveReviewEditor()) return;
        currentReviewAdminQuery = query;
        long ticket = ++generation;
        view.page(reviewAdminView);
        view.status("正在读取评价管理数据…", false);
        request(ticket, () -> gateway.adminReviews(query.keyword(), query.status(), query.page()), page -> {
            reviewAdminView.show(page); view.status("", false);
        }, error -> view.status(message(error), true));
    }

    private void openNewProduct() {
        requireFx(); requireManager();
        if (!confirmLeaveReviewEditor()) return;
        ++generation; editorView.showNew(); view.page(editorView); view.status("", false);
    }

    private void openAdminProduct(long productId) {
        requireFx(); requireManager();
        if (!confirmLeaveReviewEditor()) return;
        long ticket = ++generation; view.status("正在读取商品…", false);
        request(ticket, () -> gateway.product(productId), detail -> {
            editorView.show(detail); view.page(editorView); view.status("", false);
            loadEditorImages(detail, ticket);
        }, error -> view.status(message(error), true));
    }

    private void previewUpload(java.nio.file.Path path) {
        long ticket = generation;
        try {
            executor.execute(() -> {
                try {
                    if (java.nio.file.Files.size(path) > 2L * 1024 * 1024) {
                        throw new IllegalArgumentException("图片大小必须在 2 MiB 以内");
                    }
                    byte[] bytes = java.nio.file.Files.readAllBytes(path);
                    Platform.runLater(() -> {
                        if (valid(ticket)) { editorView.showUploadPreview(path, bytes); view.status("已选择 " + path.getFileName(), false); }
                    });
                } catch (Throwable error) {
                    Platform.runLater(() -> { if (valid(ticket)) view.status(message(error), true); });
                }
            });
        } catch (RejectedExecutionException error) { view.status(message(error), true); }
    }

    private void loadEditorImages(ProductDetail detail, long ticket) {
        for (ImageRef image : detail.images()) cache.load(image, ShopData.ImageVariant.THUMBNAIL).whenComplete((bytes, error) ->
                Platform.runLater(() -> {
                    if (valid(ticket) && error == null) editorView.showImage(image.id(), bytes);
                }));
    }

    private void saveAdminProduct(ShopData.ProductInput input, ShopImageDraft draft) {
        requireFx(); requireManager();
        long ticket = ++generation; editorView.busy(true); view.status("正在保存商品基础信息…", false);
        request(ticket, () -> gateway.saveProduct(input), productId -> {
            editorView.assignProductId(productId);
            uploadDraft(productId, draft, 0, new java.util.ArrayList<>(), ticket);
        }, error -> { editorView.busy(false); view.status(message(error), true); });
    }

    private void uploadDraft(long productId, ShopImageDraft draft, int index,
                             java.util.ArrayList<ShopData.ImagePlanItem> plan, long ticket) {
        if (!valid(ticket)) return;
        if (index >= draft.images().size()) {
            request(ticket, () -> gateway.commitImages(productId, plan), refs -> {
                editorView.busy(false); view.status("商品与图片已保存", false); cache.clearMemory();
                openAdminProducts(defaultProductQuery());
            }, error -> { editorView.busy(false); view.status(message(error), true); });
            return;
        }
        var image = draft.images().get(index);
        if (image.kind() == ShopImageDraft.Kind.EXISTING) {
            plan.add(new ShopData.ImagePlanItem(image.existingImageId(), null, image.cover()));
            uploadDraft(productId, draft, index + 1, plan, ticket); return;
        }
        if (image.uploadId() != null) {
            plan.add(new ShopData.ImagePlanItem(null, image.uploadId(), image.cover()));
            uploadDraft(productId, draft, index + 1, plan, ticket); return;
        }
        view.status("正在上传图片 " + (index + 1) + " / " + draft.images().size(), false);
        uploader.upload(image.file(), productId, progress -> { }).whenComplete((uploadId, error) ->
                Platform.runLater(() -> {
                    if (!valid(ticket)) return;
                    if (error != null) { editorView.busy(false); view.status(message(error), true); return; }
                    draft.markUploaded(image.key(), uploadId);
                    plan.add(new ShopData.ImagePlanItem(null, uploadId, image.cover()));
                    uploadDraft(productId, draft, index + 1, plan, ticket);
                }));
    }

    private void setAdminProductEnabled(long productId, boolean enabled) {
        requireFx(); requireManager(); long ticket = ++generation;
        request(ticket, () -> gateway.setProductEnabled(productId, enabled), ignored -> openAdminProducts(defaultProductQuery()),
                error -> view.status(message(error), true));
    }

    private void adjustAdminInventory(long productId, int delta, String reason) {
        requireFx(); requireManager(); long ticket = ++generation;
        request(ticket, () -> gateway.adjustInventory(productId, delta, reason),
                ignored -> openAdminProducts(defaultProductQuery()), error -> view.status(message(error), true));
    }

    private void shipAdminOrder(long orderId) {
        requireFx(); requireManager(); long ticket = ++generation;
        request(ticket, () -> gateway.shipOrder(orderId), receipt -> {
            unreadRefresh.run(); openAdminOrders(new ShopAdminView.OrderQuery("", null, 1));
        }, error -> view.status(message(error), true));
    }

    private void moderateReview(long reviewId, String reason, boolean hide) {
        requireFx(); requireManager();
        long ticket = ++generation;
        request(ticket, () -> hide ? gateway.hideReview(reviewId, reason)
                        : gateway.restoreReview(reviewId, reason),
                ignored -> openAdminReviews(currentReviewAdminQuery),
                error -> view.status(message(error), true));
    }

    private void requireManager() {
        if (!ShopAccessPolicy.canManage(roles)) throw new IllegalStateException("当前账号没有商店管理权限");
    }

    private void loadCatalogImages(ProductPage page, long ticket) {
        for (var product : page.rows()) {
            ImageRef cover = product.cover();
            if (cover == null) continue;
            cache.load(cover, ShopData.ImageVariant.THUMBNAIL).whenComplete((bytes, error) ->
                    Platform.runLater(() -> {
                        if (valid(ticket) && error == null) view.catalog().showThumbnail(product.id(), bytes);
                    }));
        }
    }

    private void loadDetailImages(ProductDetail detail, long ticket) {
        for (ImageRef image : detail.images()) {
            cache.load(image, ShopData.ImageVariant.DETAIL).whenComplete((bytes, error) ->
                    Platform.runLater(() -> {
                        if (valid(ticket) && error == null) view.detail().showImage(image, bytes);
                    }));
        }
    }

    private boolean confirmLeaveReviewEditor() {
        if (!view.isPage(reviewEditorView) || !reviewEditorView.isDirty()) return true;
        ButtonType discard = new ButtonType("放弃修改", ButtonBar.ButtonData.OK_DONE);
        ButtonType stay = new ButtonType("继续编辑", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "当前评价尚未提交，离开后本次修改将丢失。", discard, stay);
        alert.setTitle("未保存的评价");
        alert.setHeaderText("确定离开评价编辑页吗？");
        if (view.getScene() != null) alert.initOwner(view.getScene().getWindow());
        boolean leave = alert.showAndWait().filter(discard::equals).isPresent();
        if (leave) reviewEditorView.markSaved();
        return leave;
    }

    private void loadReviews(long productId, int page, long ticket) {
        request(ticket, () -> gateway.reviews(productId, page), result -> {
            view.detail().reviews().show(productId, result);
            loadReviewImages(result, ticket);
        }, error -> view.detail().reviews().showFailure(productId, message(error),
                () -> reviewPage(productId, page)));
    }

    private void loadReviewImages(ShopData.ReviewPage page, long ticket) {
        for (ShopData.Review review : page.rows()) {
            for (ShopData.ReviewImageRef image : review.images()) {
                reviewCache.load(image, ShopData.ImageVariant.THUMBNAIL)
                        .thenApplyAsync(ShopController::decodeReviewImage, executor)
                        .whenComplete((loaded, error) ->
                        Platform.runLater(() -> {
                            if (valid(ticket) && error == null) view.detail().reviews().showImage(image, loaded);
                        }));
            }
        }
    }

    private void loadReviewEditorImages(ShopData.Review review, long ticket) {
        for (ShopData.ReviewImageRef image : review.images()) {
            reviewCache.load(image, ShopData.ImageVariant.THUMBNAIL)
                    .thenApplyAsync(ShopController::decodeReviewImage, executor)
                    .whenComplete((loaded, error) -> Platform.runLater(() -> {
                        if (valid(ticket) && error == null && view.isPage(reviewEditorView)) {
                            reviewEditorView.showRetainedImage(image.id(), loaded);
                        }
                    }));
        }
    }

    private static javafx.scene.image.Image decodeReviewImage(byte[] bytes) {
        javafx.scene.image.Image image = new javafx.scene.image.Image(
                new java.io.ByteArrayInputStream(bytes));
        if (image.isError() || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new IllegalArgumentException("评价图片暂不可用");
        }
        return image;
    }

    private <T> void request(long ticket, Callable<T> work, Consumer<T> success,
                             Consumer<Throwable> failure) {
        try {
            executor.execute(() -> {
                try {
                    T result = work.call();
                    Platform.runLater(() -> { if (valid(ticket)) success.accept(result); });
                } catch (Throwable error) {
                    Platform.runLater(() -> { if (valid(ticket)) failure.accept(error); });
                }
            });
        } catch (RejectedExecutionException error) {
            if (valid(ticket)) failure.accept(error);
        }
    }

    private boolean valid(long ticket) { return !closed && active && ticket == generation; }

    static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            if (cause instanceof ShopTransportException) return "无法连接校园商店，请稍后重试";
            cause = cause.getCause();
        }
        if (cause instanceof ShopTransportException) return "无法连接校园商店，请稍后重试";
        return Objects.requireNonNullElse(cause.getMessage(), "操作失败，请稍后重试");
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("商店界面必须在 JavaFX 线程操作");
    }
}
