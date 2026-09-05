package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.*;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopVisualTest {
    private static final Path OUTPUT = Path.of("..", "docs", "design", "javafx-shop", "screenshots").normalize();

    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test void renderApprovedStorefrontPagesAndEdgeStates() throws Exception {
        fx(() -> {
            for (int[] size : List.of(new int[]{1440, 900}, new int[]{1280, 800}, new int[]{1000, 720})) {
                int width = size[0], height = size[1];
                ShopView root = shell();
                ProductPage page = products(); root.catalog().show(page); root.showCatalog();
                root.catalog().showThumbnail(1, picture(55, 118, 214));
                root.catalog().showThumbnail(2, picture(225, 116, 70));
                capture(root, "catalog-" + width, width, height);

                root = shell(); root.detail().show(detail(product(1, "VCampus A5 横线笔记本", 48, true, image(1))));
                root.detail().showImage(image(1), picture(55, 118, 214)); root.showDetail();
                capture(root, "detail-" + width, width, height);

                ShopCartView cart = cart(); root = shell(); root.page(cart); cart.show(cartData());
                capture(root, "cart-" + width, width, height);

                ShopCheckoutView checkout = new ShopCheckoutView(value -> { }, () -> { });
                checkout.show(checkoutDraft()); root = shell(); root.page(checkout);
                capture(root, "checkout-" + width, width, height);

                ShopProductEditorView editor = new ShopProductEditorView((input, draft) -> { }, () -> { }, path -> { });
                editor.show(detail(product(1, "VCampus A5 横线笔记本", 48, true, image(1))));
                editor.showImage(1, picture(55, 118, 214));
                root = shell(UserRole.TEACHER, UserRole.SHOP_ADMIN); root.page(editor);
                capture(root, "editor-" + width, width, height);
            }

            ShopView root = shell(); root.catalog().show(new ProductPage(List.of(), 1, 12, 0)); root.showCatalog();
            capture(root, "state-empty", 1000, 720);
            root = shell(); root.catalog().show(new ProductPage(List.of(product(6,
                    "这是一件用于验证极长商品名称在窄窗口中仍然完整换行且不会挤压价格与库存信息的校园纪念商品", 8, true, null)), 1, 12, 1));
            root.showCatalog(); capture(root, "state-long-name", 1000, 720);
            root = shell(); root.detail().show(detail(product(3, "没有上传图片的商品", 8, true, null))); root.showDetail();
            capture(root, "state-no-image", 1000, 720);
            root = shell(); root.detail().show(detail(product(4, "图片文件损坏时仍可购买的商品", 8, true, image(4))));
            root.detail().showImage(image(4), new byte[]{1, 2, 3}); root.showDetail();
            capture(root, "state-broken-image", 1000, 720);
            root = shell(); root.detail().show(detail(product(5, "已经下架的商品", 8, false, null))); root.showDetail();
            capture(root, "state-disabled", 1000, 720);
            ShopCartView insufficient = cart(); insufficient.show(new Cart(List.of(cartItem(8, "库存不足商品", 7, 2, true)), new BigDecimal("69.30")));
            root = shell(); root.page(insufficient); capture(root, "state-insufficient-stock", 1000, 720);
            return null;
        });
    }

    private static ShopView shell(UserRole... roles) { return new ShopView(Set.of(roles.length == 0 ? new UserRole[]{UserRole.STUDENT} : roles), new Listener()); }
    private static ProductPage products() {
        return new ProductPage(List.of(product(1, "VCampus A5 横线笔记本", 48, true, image(1)),
                product(2, "校园纪念马克杯", 21, true, image(2)),
                product(3, "蓝色中性笔五支装", 80, true, null),
                product(4, "Type-C 多功能转换器", 0, true, null),
                product(5, "校园帆布袋", 16, true, null),
                product(6, "桌面收纳盒", 30, true, null)), 1, 12, 6);
    }
    private static Product product(long id, String name, int stock, boolean enabled, ImageRef cover) {
        return new Product(id, "SKU-%06d".formatted(id), name,
                "适合日常学习与校园生活的示例商品。商品信息、价格与库存均来自应用服务器。",
                id % 2 == 0 ? ShopCategory.CAMPUS_MERCH : ShopCategory.LEARNING_STATIONERY,
                new BigDecimal(id % 2 == 0 ? "35.00" : "12.80"), stock, enabled,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), cover);
    }
    private static ImageRef image(long id) { return new ImageRef(id, "image/png", 256, "%064x".formatted(id), 0, true); }
    private static ProductDetail detail(Product product) { return new ProductDetail(product, product.cover() == null ? List.of() : List.of(product.cover())); }
    private static CartItem cartItem(long id, String name, int quantity, int stock, boolean enabled) {
        BigDecimal price = new BigDecimal("9.90");
        return new CartItem(id, "SKU-%06d".formatted(id), name, "", price, quantity, stock, enabled,
                price.multiply(BigDecimal.valueOf(quantity)), Instant.parse("2026-09-01T00:00:00Z"));
    }
    private static Cart cartData() {
        return new Cart(List.of(cartItem(1, "VCampus A5 横线笔记本", 2, 48, true),
                cartItem(2, "校园纪念马克杯", 1, 0, true), cartItem(3, "已下架的校园帆布袋", 1, 16, false)),
                new BigDecimal("39.60"));
    }
    private static ShopCartView cart() { return new ShopCartView(new ShopCartView.Listener() {
        public void quantity(long id, int value) { } public void remove(long id) { }
        public void checkout(Set<Long> ids) { }
    }); }
    private static ShopCheckoutView.CheckoutDraft checkoutDraft() {
        var lines = List.of(new ShopCheckoutView.CheckoutLine(1, "VCampus A5 横线笔记本", new BigDecimal("12.80"), 2, 48, true),
                new ShopCheckoutView.CheckoutLine(2, "校园纪念马克杯", new BigDecimal("35.00"), 1, 21, true));
        return new ShopCheckoutView.CheckoutDraft(ShopCheckoutView.Source.CART, lines,
                new BigDecimal("60.60"), new BigDecimal("286.50"), "00000000-0000-0000-0000-000000000001");
    }
    private static byte[] picture(int red, int green, int blue) throws Exception {
        var image = new java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(new java.awt.Color(red, green, blue)); graphics.fillRect(0, 0, 640, 480);
        graphics.setColor(java.awt.Color.WHITE); graphics.fillRoundRect(175, 120, 290, 240, 36, 36); graphics.dispose();
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
    private static void capture(Parent root, String name, int width, int height) throws Exception {
        if (root.getScene() != null) root.getScene().setRoot(new Group());
        Scene scene = new Scene(root, width, height); root.resize(width, height); root.applyCss(); root.layout();
        WritableImage image = root.snapshot(null, null);
        assertEquals(width, image.getWidth(), 1); assertEquals(height, image.getHeight(), 1);
        Files.createDirectories(OUTPUT); ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", OUTPUT.resolve(name + ".png").toFile());
        scene.setRoot(new Group());
    }
    private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(60, TimeUnit.SECONDS);
    }
    private static final class Listener implements ShopView.Listener {
        public void back() { } public void search(String keyword, ShopCategory category, ShopProductSort sort, int page) { }
        public void product(long id) { } public void addToCart(long id, int quantity) { }
        public void buyNow(ShopDetailView.BuyNowSelection selection) { }
    }
}
