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
        ++generation;
        view.showComingSoon("已选择立即购买：商品 " + selection.productId() + " × " + selection.quantity());
        view.status("下一阶段将进入订单确认页；当前没有扣款，也没有修改购物车。", false);
    }

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
