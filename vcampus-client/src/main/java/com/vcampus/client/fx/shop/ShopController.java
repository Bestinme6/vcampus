package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.client.fx.shop.ShopData.ProductDetail;
import com.vcampus.client.fx.shop.ShopData.ProductPage;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Session-scoped JavaFX shop coordinator. Public lifecycle methods are FX-thread confined. */
public final class ShopController implements AutoCloseable, ShopView.Listener {
    private final ShopGateway gateway;
    private final Set<UserRole> roles;
    private final Executor executor;
    private final ShopImageCache cache;
    private final Runnable back;
    private final Runnable unreadRefresh;
    private final ShopView view;
    private final ShopCartView cartView;
    private final ShopCheckoutView checkoutView;
    private final ShopOrdersView ordersView;
    private final ShopOrderDetailView orderDetailView;
    private ShopData.Cart currentCart;
    private long generation;
    private boolean active = true;
    private boolean closed;

    public ShopController(ShopGateway gateway, Set<UserRole> roles, Executor executor,
                          ShopImageCache cache, Runnable back, Runnable unreadRefresh) {
        requireFx();
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.roles = Set.copyOf(roles);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.back = Objects.requireNonNull(back, "back");
        this.unreadRefresh = Objects.requireNonNull(unreadRefresh, "unreadRefresh");
        this.view = new ShopView(this);
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
        });
    }

    public Parent view() { requireFx(); return view; }

    public void open(String route) {
        requireFx();
        if (closed) return;
        active = true;
        if (route == null || route.equals("shop") || route.equals("catalog")) {
            search("", null, ShopProductSort.NEWEST, 1);
        } else if (route.startsWith("product/")) {
            try { openProduct(Long.parseLong(route.substring("product/".length()))); }
            catch (NumberFormatException error) { view.status("商品地址无效", true); }
        } else if (route.equals("cart")) {
            openCart();
        } else if (route.equals("orders")) {
            openOrders(1);
        } else if (route.startsWith("order/")) {
            try { openOrder(Long.parseLong(route.substring("order/".length()))); }
            catch (NumberFormatException error) { view.status("订单地址无效", true); }
        } else if (route.startsWith("checkout/direct/")) {
            String[] values = route.substring("checkout/direct/".length()).split("/");
            try { openDirectCheckout(Long.parseLong(values[0]), Integer.parseInt(values[1])); }
            catch (RuntimeException error) { view.status("立即购买地址无效", true); }
        } else if (route.equals("checkout/cart")) {
            if (currentCart == null) openCart(); else openCartCheckout(cartView.selectedProductIds());
        } else {
            view.status("没有找到要打开的商店页面", true);
        }
    }

    @Override public void search(String keyword, ShopCategory category, ShopProductSort sort, int page) {
        requireFx();
        if (closed || !active) return;
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
        if (closed || !active) return;
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
        }, error -> {
            view.busy(false);
            view.showCatalog();
            view.catalog().showFailure(message(error), () -> openProduct(productId));
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
        if (closed || !active) return;
        openDirectCheckout(selection.productId(), selection.quantity());
    }

    @Override public void cart() { openCart(); }
    @Override public void orders() { openOrders(1); }

    @Override public void back() { requireFx(); if (!closed) back.run(); }

    public void deactivate() {
        requireFx();
        if (closed) return;
        active = false;
        ++generation;
        view.busy(false);
    }

    @Override public void close() {
        requireFx();
        if (closed) return;
        active = false;
        closed = true;
        ++generation;
        cache.close();
    }

    ShopCatalogView catalogView() { requireFx(); return view.catalog(); }

    private void openCart() {
        requireFx();
        if (closed || !active) return;
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
        request(ticket, () -> new DirectCheckoutData(gateway.product(productId), gateway.bankBalance()), data -> {
            var product = data.detail().product();
            var line = new ShopCheckoutView.CheckoutLine(product.id(), product.name(), product.price(),
                    quantity, product.stock(), product.enabled());
            showCheckout(new ShopCheckoutView.CheckoutDraft(ShopCheckoutView.Source.DIRECT, List.of(line),
                    line.subtotal(), data.balance(), UUID.randomUUID().toString()));
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
        request(ticket, gateway::bankBalance, balance -> {
            var total = lines.stream().map(ShopCheckoutView.CheckoutLine::subtotal)
                    .reduce(new java.math.BigDecimal("0.00"), java.math.BigDecimal::add);
            showCheckout(new ShopCheckoutView.CheckoutDraft(ShopCheckoutView.Source.CART, lines,
                    total, balance, UUID.randomUUID().toString()));
        }, error -> view.status(message(error), true));
    }

    private void showCheckout(ShopCheckoutView.CheckoutDraft draft) {
        checkoutView.show(draft); view.page(checkoutView); view.status("", false);
    }

    private void confirmCheckout(ShopCheckoutView.CheckoutDraft draft) {
        requireFx();
        long ticket = ++generation;
        request(ticket, () -> draft.source() == ShopCheckoutView.Source.DIRECT
                ? gateway.buyNow(draft.items().getFirst().productId(), draft.items().getFirst().quantity(), draft.operationId())
                : gateway.checkout(draft.items().stream().map(ShopCheckoutView.CheckoutLine::productId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()), draft.operationId()), receipt -> {
            unreadRefresh.run(); currentCart = null; openOrder(receipt.orderId());
        }, error -> {
            String text = message(error);
            if (isDraftChange(error)) {
                view.status("商品价格或库存已变化，请重新确认", true);
                if (draft.source() == ShopCheckoutView.Source.DIRECT) {
                    var item = draft.items().getFirst(); openDirectCheckout(item.productId(), item.quantity());
                } else openCart();
            } else checkoutView.failure(text);
        });
    }

    private void returnFromCheckout() {
        requireFx();
        if (checkoutView.draft() != null && checkoutView.draft().source() == ShopCheckoutView.Source.CART) openCart();
        else if (checkoutView.draft() != null) openProduct(checkoutView.draft().items().getFirst().productId());
        else search("", null, ShopProductSort.NEWEST, 1);
    }

    private void openOrders(int page) {
        requireFx();
        long ticket = ++generation; view.page(ordersView); view.status("正在读取订单…", false);
        request(ticket, () -> gateway.orders(null, page), orders -> {
            ordersView.show(orders); view.status("", false);
        }, error -> view.status(message(error), true));
    }

    private void openOrder(long orderId) {
        requireFx();
        if (orderId < 1) { view.status("订单编号无效", true); return; }
        long ticket = ++generation; view.status("正在读取订单详情…", false);
        request(ticket, () -> gateway.order(orderId), detail -> {
            orderDetailView.show(detail); view.page(orderDetailView); view.status("", false);
        }, error -> view.status(message(error), true));
    }

    private void cancelOrder(long orderId) { mutateOrder(() -> gateway.cancelOrder(orderId), orderId); }
    private void confirmOrder(long orderId) { mutateOrder(() -> gateway.confirmOrder(orderId), orderId); }

    private void mutateOrder(Callable<ShopData.CheckoutReceipt> mutation, long orderId) {
        requireFx();
        long ticket = ++generation; view.busy(true);
        request(ticket, mutation, receipt -> { view.busy(false); unreadRefresh.run(); openOrder(orderId); },
                error -> { view.busy(false); view.status(message(error), true); });
    }

    private static boolean isDraftChange(Throwable error) {
        String value = Objects.toString(error.getMessage(), "");
        return value.contains("价格") || value.contains("库存") || value.contains("下架") || value.contains("商品状态");
    }

    private record DirectCheckoutData(ProductDetail detail, java.math.BigDecimal balance) { }

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

    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof IOException) return "无法连接校园商店，请稍后重试";
        return Objects.requireNonNullElse(cause.getMessage(), "操作失败，请稍后重试");
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("商店界面必须在 JavaFX 线程操作");
    }
}
