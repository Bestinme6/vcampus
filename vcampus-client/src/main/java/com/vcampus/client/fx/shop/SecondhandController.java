package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.SecondhandCategory;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.Set;
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
    private final long userId;
    private final Set<UserRole> roles;
    private final BorderPane root = new BorderPane();
    private final Label status = ShopUi.label("", "shop-status");
    private final SecondhandCatalogView catalog;
    private final SecondhandDetailView detail;
    private final SecondhandCheckoutView checkout;
    private final SecondhandSellerView seller;
    private final SecondhandItemEditorView editor;
    private final SecondhandReviewView reviews;
    private final SecondhandAdminView admin;
    private final SecondhandImageUploader imageUploader;
    private final SecondhandImageCache imageCache;
    private final SecondhandReviewImageUploader reviewImageUploader;
    private final VBox orders = new VBox(10);
    private SecondhandData.Listing currentListing;
    private SecondhandData.Order currentOrder;
    private long currentReviewSellerId;
    private volatile String preparedOperationId;
    private volatile ShippingAddressChoice preparedAddress;
    private long generation;
    private boolean closed;

    public SecondhandController(SecondhandGateway gateway, ShopGateway shopGateway, long userId,
                                Executor executor, Runnable unreadRefresh) {
        this(gateway,shopGateway,userId,Set.of(),executor,unreadRefresh);
    }

    public SecondhandController(SecondhandGateway gateway, ShopGateway shopGateway, long userId,
                                Set<UserRole> roles, Executor executor, Runnable unreadRefresh) {
        requireFx();
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.shopGateway = Objects.requireNonNull(shopGateway, "shopGateway");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.unreadRefresh = Objects.requireNonNull(unreadRefresh, "unreadRefresh");
        this.userId=userId;
        this.roles=Set.copyOf(roles);
        this.catalog = new SecondhandCatalogView(this::search, this::open, () -> openOrders(1));
        this.detail = new SecondhandDetailView(userId, this::detailAction,
                () -> { if (currentOrder == null) search("", 1); else if(currentOrder.sellerId()==userId)openSellingOrders(1);else openOrders(1); },
                this::editItem,this::openSellerReviews,this::openOrderReview);
        this.checkout = new SecondhandCheckoutView(this::buy, () -> {
            if (currentListing != null) showListing(currentListing);
            else search("", 1);
        });
        this.seller=new SecondhandSellerView(userId,this::shipOrder);
        this.editor=new SecondhandItemEditorView(userId,this::saveItem,this::withdrawItem,
                ()->{if(currentListing==null)search("",1);else showListing(currentListing);},this::uploadItemImage);
        this.reviews=new SecondhandReviewView(userId,this::createReview,this::likeReview,this::uploadReviewImage);
        this.imageUploader=new SecondhandImageUploader(gateway,executor);
        this.imageCache=new SecondhandImageCache(gateway,executor);
        this.reviewImageUploader=new SecondhandReviewImageUploader(gateway,executor);
        this.admin=ShopAccessPolicy.canManage(this.roles)?new SecondhandAdminView(this.roles,new SecondhandAdminView.Listener(){
            @Override public void moderateItem(long id,boolean hide,String reason){SecondhandController.this.moderateItem(id,hide,reason);}
            @Override public void resolve(long id,SecondhandData.AdminResolution resolution,String operationId,String reason){SecondhandController.this.resolveOrder(id,resolution,operationId,reason);}
            @Override public void moderateReview(long id,boolean hide,String reason){SecondhandController.this.moderateReview(id,hide,reason);}
            @Override public void orders(){SecondhandController.this.openAdminOrders();}
            @Override public void order(long id){SecondhandController.this.openAdminOrder(id);}
            @Override public void items(String keyword,int page){openAdminItems(keyword,page);}
            @Override public void reviews(String keyword,int page){openAdminReviews(keyword,page);}
        }):null;
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
        Button market=ShopUi.button("二手市场","shop-tab",()->search("",1));market.setId("used-nav-market");
        Button publish=ShopUi.button("发布物品","shop-tab",this::newItem);publish.setId("used-nav-publish");
        Button buying=ShopUi.button("我的购买","shop-tab",()->openOrders(1));buying.setId("used-nav-buying");
        Button selling=ShopUi.button("我的出售","shop-tab",()->openSellingOrders(1));selling.setId("used-nav-selling");
        HBox navigation=new HBox(8,market,publish,buying,selling);navigation.getStyleClass().add("used-navigation");
        if(admin!=null){Button manage=ShopUi.button("二手管理","shop-tab",this::openAdmin);manage.setId("used-nav-admin");navigation.getChildren().add(manage);}
        root.setTop(navigation);
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
        search(keyword, null, page);
    }

    private void search(String keyword, SecondhandCategory category, int page) {
        requireFx();
        if (closed) return;
        long ticket = ++generation;
        currentListing = null;
        currentOrder = null;
        catalog.loading();
        root.setCenter(catalog);
        showStatus("", false);
        request(ticket, () -> gateway.search(keyword, category, page), result->{catalog.show(result);loadCatalogImages(result,ticket);loadSellerRatings(result,ticket);},
                error -> catalog.failure(message(error)));
    }

    private void showListing(SecondhandData.Listing listing) {
        currentListing = listing;
        currentOrder = null;
        detail.show(listing);
        root.setCenter(detail);
        loadDetailImages(listing, generation);
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

    private void loadSellerRatings(SecondhandData.ListingPage page,long ticket){
        page.rows().stream().map(SecondhandData.Listing::sellerId).distinct().forEach(sellerId->
                request(ticket,()->gateway.reviews(sellerId,1),result->{
                    for(var item:page.rows())if(item.sellerId()==sellerId)catalog.showRating(item.id(),result.summary().average(),result.summary().count());
                },error->{for(var item:page.rows())if(item.sellerId()==sellerId)catalog.showRating(item.id(),null,0);}));
    }

    private void newItem(){requireFx();++generation;editor.busy(false);currentListing=null;currentOrder=null;editor.showNew();root.setCenter(editor);showStatus("",false);}

    private void editItem(long itemId){requireFx();if(currentListing==null||currentListing.id()!=itemId)return;editor.show(currentListing);root.setCenter(editor);loadEditorImages(currentListing,generation);showStatus("",false);}

    private void saveItem(SecondhandData.ListingDraft draft,SecondhandImageDraft images){
        requireFx();long ticket=++generation;editor.busy(true);showStatus("正在保存二手物品…",false);
        request(ticket,()->draft.itemId()==null?gateway.create(draft):saveExisting(draft),itemId->{
            editor.assignItemId(itemId);showStatus("正在上传并整理物品图片…",false);
            uploadNextImage(itemId, images, ticket);
        },error->{editor.busy(false);showStatus(message(error),true);});
    }

    private long saveExisting(SecondhandData.ListingDraft draft)throws Exception{gateway.update(draft.itemId(),draft);return draft.itemId();}

    private void withdrawItem(long itemId){
        requireFx();long ticket=++generation;editor.busy(true);
        request(ticket,()->gateway.withdraw(itemId),listing->{editor.busy(false);currentListing=listing;showListing(listing);showStatus("物品已下架",false);},error->{editor.busy(false);showStatus(message(error),true);});
    }

    private void uploadItemImage(java.nio.file.Path file){
        requireFx();long ticket=generation;
        try{executor.execute(()->{try{long size=java.nio.file.Files.size(file);if(size>2L*1024*1024)throw new java.io.IOException("图片大小必须在 2 MiB 以内");byte[]bytes=java.nio.file.Files.readAllBytes(file);Platform.runLater(()->{if(valid(ticket))editor.showUploadPreview(file,bytes);});}catch(Throwable error){Platform.runLater(()->{if(valid(ticket))showStatus(message(error),true);});}});}catch(RejectedExecutionException error){showStatus(message(error),true);}
    }

    private void uploadNextImage(long itemId, SecondhandImageDraft images, long ticket) {
        if (!valid(ticket)) return;
        var pending = images.pendingUploads();
        if (pending.isEmpty()) {
            var plan = images.plan();
            request(ticket, () -> gateway.uploadImages(itemId, plan), saved -> {
                editor.busy(false);
                open(itemId);
            }, error -> {
                images.resetUploads();
                editor.busy(false);
                showStatus(message(error), true);
            });
            return;
        }
        var image = pending.getFirst();
        imageUploader.upload(image.file(), itemId, progress -> { }).whenComplete((uploadId, error) ->
                Platform.runLater(() -> {
                    if (!valid(ticket)) return;
                    if (error != null) {
                        editor.busy(false);
                        showStatus(message(error), true);
                        return;
                    }
                    images.markUploaded(image.key(), uploadId);
                    uploadNextImage(itemId, images, ticket);
                }));
    }

    private void loadCatalogImages(SecondhandData.ListingPage page,long ticket){for(var item:page.rows()){if(item.images().isEmpty())continue;imageCache.load(item.images().getFirst(),ShopData.ImageVariant.THUMBNAIL).whenComplete((bytes,error)->Platform.runLater(()->{if(valid(ticket)&&error==null)catalog.showImage(item.id(),bytes);}));}}
    private void loadDetailImages(SecondhandData.Listing listing,long ticket){for(var image:listing.images())imageCache.load(image,ShopData.ImageVariant.DETAIL).whenComplete((bytes,error)->Platform.runLater(()->{if(valid(ticket)&&error==null)detail.showImage(image.id(),bytes);}));}
    private void loadEditorImages(SecondhandData.Listing listing,long ticket){for(var image:listing.images())imageCache.load(image,ShopData.ImageVariant.THUMBNAIL).whenComplete((bytes,error)->Platform.runLater(()->{if(valid(ticket)&&error==null)editor.showImage(image.id(),bytes);}));}

    private void openSellingOrders(int page){
        requireFx();long ticket=++generation;currentListing=null;currentOrder=null;seller.getChildren().setAll(ShopUi.label("正在读取出售订单…","shop-muted"));root.setCenter(seller);
        request(ticket,()->gateway.orders(true,page),result->seller.showOrders(result,this::openOrder),error->showStatus(message(error),true));
    }

    private void shipOrder(long orderId){
        requireFx();long ticket=++generation;seller.setDisable(true);
        request(ticket,()->gateway.ship(orderId),ignored->{seller.setDisable(false);unreadRefresh.run();openOrder(orderId);},error->{seller.setDisable(false);showStatus(message(error),true);});
    }

    private void openSellerReviews(long sellerId){
        requireFx();currentReviewSellerId=sellerId;long ticket=++generation;reviews.getChildren().setAll(ShopUi.label("正在读取卖家评价…","shop-muted"));root.setCenter(reviews);
        request(ticket,()->gateway.reviews(sellerId,1),page->{reviews.showSeller(sellerId,page);showStatus("",false);},error->showStatus(message(error),true));
    }

    private void openOrderReview(long orderId){
        requireFx();if(currentOrder==null||currentOrder.id()!=orderId)return;SecondhandData.Order order=currentOrder;long ticket=++generation;showStatus("正在检查评价状态…",false);
        request(ticket,()->findReview(order),existing->{reviews.showOrder(order,existing);root.setCenter(reviews);showStatus("",false);},error->showStatus(message(error),true));
    }

    private Long findReview(SecondhandData.Order order)throws Exception{
        int page=1;while(true){var result=gateway.reviews(order.sellerId(),page);var found=result.rows().stream().filter(row->row.orderId()==order.id()&&row.authorId()==userId).findFirst();if(found.isPresent())return found.get().id();if(page*result.pageSize()>=result.total())return null;page++;}
    }

    private void createReview(SecondhandData.ReviewDraft draft){
        requireFx();long sellerId=currentOrder==null?0:currentOrder.sellerId();long ticket=++generation;reviews.busy(true);
        request(ticket,()->gateway.createReview(draft),ignored->{reviews.busy(false);unreadRefresh.run();openSellerReviews(sellerId);},error->{reviews.busy(false);showStatus(message(error),true);});
    }

    private void likeReview(long reviewId,boolean enabled){
        requireFx();long ticket=generation;request(ticket,()->gateway.likeReview(reviewId,enabled),ignored->{showStatus(enabled?"已标记有帮助":"已取消有帮助",false);if(currentReviewSellerId>0)openSellerReviews(currentReviewSellerId);},error->showStatus(message(error),true));
    }

    private void uploadReviewImage(java.nio.file.Path file){
        requireFx();long ticket=generation;reviews.busy(true);reviewImageUploader.upload(file,value->{ }).whenComplete((uploadId,error)->Platform.runLater(()->{if(!valid(ticket))return;reviews.busy(false);if(error==null){reviews.addUpload(uploadId);showStatus("评价图片已上传",false);}else showStatus(message(error),true);}));
    }

    private void openAdmin(){requireFx();if(admin==null){showStatus("没有二手市场管理权限",true);return;}openAdminItems("",1);}

    private void openAdminItems(String keyword,int page) {
        requireFx(); if(admin==null || closed)return;
        long ticket=++generation;
        admin.setDisable(false); admin.showList(true,keyword,page); root.setCenter(admin); showStatus("",false);
        request(ticket,()->gateway.adminItems(keyword,page),admin::showItems,error->admin.failure(message(error)));
    }

    private record AdminReviews(SecondhandData.ReviewPage page, java.util.Map<Long,SecondhandData.Listing> items) { }

    private void openAdminReviews(String keyword,int page) {
        requireFx(); if(admin==null || closed)return;
        long ticket=++generation;
        admin.setDisable(false); admin.showList(false,keyword,page); root.setCenter(admin); showStatus("",false);
        request(ticket,()->{
            var result=gateway.adminReviews(keyword,null,page);
            java.util.Map<Long,SecondhandData.Listing> items=new java.util.HashMap<>();
            for(long id:result.rows().stream().mapToLong(SecondhandData.Review::itemId).distinct().toArray())
                items.put(id,gateway.get(id));
            return new AdminReviews(result,items);
        },result->admin.showReviews(result.page(),result.items()),error->admin.failure(message(error)));
    }

    private void openAdminOrders(){requireFx();if(admin==null)return;long ticket=++generation;request(ticket,()->gateway.adminOrders("",ShopOrderStatus.SHIPPED,1),admin::showOrders,error->showStatus(message(error),true));}

    private void openAdminOrder(long orderId){requireFx();if(admin==null)return;long ticket=++generation;request(ticket,()->gateway.adminOrder(orderId),admin::showOrder,error->showStatus(message(error),true));}

    private void resolveOrder(long orderId,SecondhandData.AdminResolution resolution,String operationId,String reason){requireFx();if(admin==null)return;long ticket=++generation;admin.setDisable(true);request(ticket,()->gateway.adminResolve(orderId,resolution,operationId,reason),order->{admin.setDisable(false);unreadRefresh.run();admin.showOrder(order);showStatus("异常订单已处理",false);},error->{admin.setDisable(false);showStatus(message(error),true);});}

    private void moderateItem(long itemId,boolean hide,String reason){requireFx();if(admin==null)return;long ticket=++generation;admin.setDisable(true);request(ticket,()->hide?gateway.adminHideItem(itemId,reason):gateway.adminRestoreItem(itemId,reason),ignored->{admin.setDisable(false);admin.refresh();showStatus(hide?"物品已隐藏":"物品已恢复",false);},error->{admin.setDisable(false);showStatus(message(error),true);});}

    private void moderateReview(long reviewId,boolean hide,String reason){requireFx();if(admin==null)return;long ticket=++generation;admin.setDisable(true);request(ticket,()->hide?gateway.adminHideReview(reviewId,reason):gateway.adminRestoreReview(reviewId,reason),ignored->{admin.setDisable(false);admin.refresh();showStatus(hide?"评价已隐藏":"评价已恢复",false);},error->{admin.setDisable(false);showStatus(message(error),true);});}

    private void openOrder(long orderId) {
        requireFx();
        if (closed || orderId < 1) { showStatus("二手订单编号无效", true); return; }
        long ticket = ++generation;
        showStatus("正在读取二手订单…", false);
        request(ticket, () -> gateway.order(orderId), order -> {
            currentListing = null;
            currentOrder = order;
            if(order.sellerId()==userId){seller.showOrder(order);root.setCenter(seller);}
            else {detail.showOrder(order);root.setCenter(detail);}
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
        imageCache.close();
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
