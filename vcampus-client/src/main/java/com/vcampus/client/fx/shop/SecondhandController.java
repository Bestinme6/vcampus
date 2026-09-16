package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShopOrderStatus;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Coordinates buyer-facing secondhand pages on the JavaFX application thread. */
public final class SecondhandController implements AutoCloseable {
    private final SecondhandGateway gateway;
    private final ShopGateway shopGateway;
    private final Executor executor;
    private final Runnable unreadRefresh;
    private final BorderPane root = new BorderPane();
    private final Label status = ShopUi.label("", "shop-status");
    private final SecondhandCatalogView catalog;
    private final SecondhandDetailView detail;
    private final SecondhandCheckoutView checkout;
    private final VBox orders = new VBox(10);
    private SecondhandData.Listing currentListing;
    private SecondhandData.Order currentOrder;
    private volatile String preparedOperationId;
    private volatile ShippingAddressChoice preparedAddress;
    private long generation;
    private boolean closed;

    public SecondhandController(SecondhandGateway gateway, ShopGateway shopGateway, long userId,
                                Executor executor, Runnable unreadRefresh) {
        requireFx();
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.shopGateway = Objects.requireNonNull(shopGateway, "shopGateway");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.unreadRefresh = Objects.requireNonNull(unreadRefresh, "unreadRefresh");
        this.catalog = new SecondhandCatalogView(this::search, this::open, () -> openOrders(1));
        this.detail = new SecondhandDetailView(userId, this::detailAction,
                () -> { if (currentOrder == null) search("", 1); else openOrders(1); });
        this.checkout = new SecondhandCheckoutView(this::buy, () -> {
            if (currentListing != null) showListing(currentListing);
            else search("", 1);
        });
        root.setId("used-view");
        root.getStyleClass().add("used-root");
        root.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("shop.css")).toExternalForm());
        root.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("shop-address.css")).toExternalForm());
        root.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("secondhand.css")).toExternalForm());
        status.setManaged(false);
        status.setVisible(false);
        root.setBottom(status);
        orders.getStyleClass().add("used-content");
    }

    public Parent view() { requireFx(); return root; }

    public void open(String route) {
        requireFx();
        if (closed) return;
        if (route == null || route.equals("used")) search("", 1);
        else if (route.startsWith("used-order/")) {
            try { openOrder(Long.parseLong(route.substring("used-order/".length()))); }
            catch (NumberFormatException error) { showStatus("二手订单地址无效", true); }
        } else showStatus("没有找到要打开的二手页面", true);
    }

    public void open(long itemId) {
        requireFx();
        if (closed || itemId < 1) { showStatus("二手物品编号无效", true); return; }
        long ticket = ++generation;
        showStatus("正在打开二手物品…", false);
        request(ticket, () -> gateway.get(itemId), this::showListing,
                error -> showStatus(message(error), true));
    }

    private void search(String keyword, int page) {
        requireFx();
        if (closed) return;
        long ticket = ++generation;
        currentListing = null;
        currentOrder = null;
        catalog.loading();
        root.setCenter(catalog);
        showStatus("", false);
        request(ticket, () -> gateway.search(keyword, page), catalog::show,
                error -> catalog.failure(message(error)));
    }

    private void showListing(SecondhandData.Listing listing) {
        currentListing = listing;
        currentOrder = null;
        detail.show(listing);
        root.setCenter(detail);
        showStatus("", false);
    }

    private void detailAction(long id) {
        requireFx();
        if (currentOrder != null && currentOrder.id() == id) {
            if (currentOrder.status() == ShopOrderStatus.SHIPPED) confirmOrder(id);
            else if (currentOrder.status() == ShopOrderStatus.PAID) cancelOrder(id);
            return;
        }
        if (currentListing == null || currentListing.id() != id) return;
        long ticket = ++generation;
        showStatus("正在读取收货地址…", false);
        request(ticket, shopGateway::addresses, rows -> {
            preparedOperationId = UUID.randomUUID().toString();
            preparedAddress = null;
            checkout.show(currentListing, rows, preparedOperationId);
            root.setCenter(checkout);
            showStatus("", false);
        }, error -> showStatus(message(error), true));
    }

    private void buy(SecondhandCheckoutView.Submission submission) {
        requireFx();
        long ticket = ++generation;
        checkout.busy(true);
        showStatus("正在提交二手订单…", false);
        request(ticket, () -> {
            ShippingAddressChoice address = preparedAddress;
            if (!submission.operationId().equals(preparedOperationId)) {
                throw new IllegalStateException("结算请求已失效，请重新选择商品");
            }
            if (address == null) {
                address = submission.address();
                if (submission.saveAddress()) {
                    var saved = shopGateway.saveAddress(submission.replaceSlot(), address.inline());
                    address = new ShippingAddressChoice(saved.slot(), saved.version(), null);
                }
                preparedAddress = address;
            }
            return gateway.buy(submission.itemId(), submission.operationId(), address);
        }, receipt -> {
            checkout.busy(false);
            unreadRefresh.run();
            openOrder(receipt.orderId());
        }, error -> {
            checkout.showError(message(error));
            showStatus(message(error), true);
        });
    }

    private void openOrders(int page) {
        requireFx();
        if (closed) return;
        long ticket = ++generation;
        currentListing = null;
        currentOrder = null;
        orders.getChildren().setAll(ShopUi.label("正在读取二手订单…", "shop-muted"));
        root.setCenter(orders);
        request(ticket, () -> gateway.orders(false, page), this::showOrders,
                error -> orders.getChildren().setAll(ShopUi.label(message(error), "shop-error")));
    }

    private void showOrders(SecondhandData.OrderPage page) {
        Label title = ShopUi.label("我的二手订单", "used-title");
        Button market = ShopUi.button("返回二手市场", "shop-quiet", () -> search("", 1));
        orders.getChildren().setAll(title, market);
        if (page.rows().isEmpty()) {
            orders.getChildren().add(ShopUi.label("还没有二手订单", "shop-muted"));
            return;
        }
        for (SecondhandData.Order row : page.rows()) {
            Button button = ShopUi.button(row.title() + " · ¥ " + row.amount().toPlainString()
                    + " · " + row.status().name(), "used-card", () -> openOrder(row.id()));
            button.setId("used-order-" + row.id());
            orders.getChildren().add(button);
        }
    }

    private void openOrder(long orderId) {
        requireFx();
        if (closed || orderId < 1) { showStatus("二手订单编号无效", true); return; }
        long ticket = ++generation;
        showStatus("正在读取二手订单…", false);
        request(ticket, () -> gateway.order(orderId), order -> {
            currentListing = null;
            currentOrder = order;
            detail.showOrder(order);
            root.setCenter(detail);
            showStatus("", false);
        }, error -> showStatus(message(error), true));
    }

    private void cancelOrder(long orderId) { mutateOrder(() -> gateway.cancel(orderId), orderId); }
    private void confirmOrder(long orderId) { mutateOrder(() -> gateway.confirm(orderId), orderId); }

    private void mutateOrder(Callable<SecondhandData.Receipt> work, long orderId) {
        long ticket = ++generation;
        detail.setDisable(true);
        request(ticket, work, ignored -> {
            detail.setDisable(false);
            unreadRefresh.run();
            openOrder(orderId);
        }, error -> {
            detail.setDisable(false);
            showStatus(message(error), true);
        });
    }

    @Override public void close() {
        requireFx();
        if (closed) return;
        closed = true;
        ++generation;
    }

    void deactivate() {
        requireFx();
        if (closed) return;
        ++generation;
        checkout.busy(false);
        detail.setDisable(false);
    }

    private void showStatus(String text, boolean error) {
        status.setText(Objects.requireNonNullElse(text, ""));
        status.setManaged(!status.getText().isBlank());
        status.setVisible(!status.getText().isBlank());
        status.getStyleClass().remove("shop-error");
        if (error) status.getStyleClass().add("shop-error");
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

    private boolean valid(long ticket) { return !closed && ticket == generation; }
    private static String message(Throwable error) { return ShopController.message(error); }
    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("二手市场界面必须在 JavaFX 线程操作");
        }
    }
}
