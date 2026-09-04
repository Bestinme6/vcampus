package com.vcampus.client.fx.shop;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopGatewayTest {
    private static final String TOKEN = "session-token";
    private static final String HASH = "0123456789abcdef".repeat(4);

    @Test
    void catalogSearchSendsStableCategoryAndSortNames() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply(pageResponse());
        ShopGateway gateway = new SocketShopGateway(client, TOKEN);

        gateway.search("校徽", ShopCategory.CAMPUS_MERCH, ShopProductSort.PRICE_ASC, 3);

        assertRequest(client, Actions.SHOP_PRODUCT_SEARCH, Map.of(
                "sessionToken", TOKEN, "keyword", "校徽", "category", "CAMPUS_MERCH",
                "sort", "PRICE_ASC", "page", "3"));
    }

    @Test
    void buyNowUsesOnlyConstructorIdentityAndServerOwnedPrice() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply(receiptResponse());
        ShopGateway gateway = new SocketShopGateway(client, TOKEN);
        String operationId = UUID.randomUUID().toString();

        gateway.buyNow(41L, 2, operationId);

        assertRequest(client, Actions.SHOP_BUY_NOW, Map.of(
                "sessionToken", TOKEN, "productId", "41", "quantity", "2",
                "operationId", operationId));
        assertFalse(client.lastRequest().parameters().keySet().stream().anyMatch(
                key -> key.equals("buyerId") || key.equals("buyerUserId")
                        || key.equals("accountId") || key.equals("price") || key.equals("total")));
    }

    @Test
    void selectedCheckoutIsSortedAndContainsNoBuyerOrMoneyFields() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply(receiptResponse());
        ShopGateway gateway = new SocketShopGateway(client, TOKEN);
        String operationId = UUID.randomUUID().toString();

        gateway.checkout(Set.of(9L, 2L), operationId);

        assertRequest(client, Actions.SHOP_CHECKOUT, Map.of(
                "sessionToken", TOKEN, "operationId", operationId, "selectedCount", "2",
                "selected.0", "2", "selected.1", "9"));
    }

    @Test
    void imageUploadUsesExactBase64ChunkAndCompletionShapes() throws Exception {
        RecordingClient client = new RecordingClient();
        ShopGateway gateway = new SocketShopGateway(client, TOKEN);

        client.reply(ResponseMessage.success("reply", "ok", Map.of(
                "uploadId", "upload-1", "productId", "4", "expectedBytes", "3",
                "chunkBytes", "196608", "expiresAt", "2026-09-01T09:00:00Z")));
        gateway.uploadStart(4L, "image/png", 3L);
        assertRequest(client, Actions.SHOP_ADMIN_IMAGE_UPLOAD_START, Map.of(
                "sessionToken", TOKEN, "productId", "4", "mimeType", "image/png",
                "expectedBytes", "3"));

        client.reply(emptyResponse());
        gateway.uploadChunk("upload-1", 7, new byte[]{1, 2, 3});
        assertRequest(client, Actions.SHOP_ADMIN_IMAGE_UPLOAD_CHUNK, Map.of(
                "sessionToken", TOKEN, "uploadId", "upload-1", "chunkIndex", "7",
                "contentBase64", "AQID"));

        gateway.uploadComplete("upload-1");
        assertRequest(client, Actions.SHOP_ADMIN_IMAGE_UPLOAD_COMPLETE, Map.of(
                "sessionToken", TOKEN, "uploadId", "upload-1"));
    }

    @Test
    void imageCommitEncodesOrderedExclusiveSourcesWithRowCodec() throws Exception {
        RecordingClient client = new RecordingClient();
        client.reply(ResponseMessage.success("reply", "ok", Map.of("count", "0")));
        ShopGateway gateway = new SocketShopGateway(client, TOKEN);

        gateway.commitImages(4L, List.of(
                new ShopData.ImagePlanItem(9L, null, true),
                new ShopData.ImagePlanItem(null, "upload-2", false)));

        assertRequest(client, Actions.SHOP_ADMIN_IMAGE_COMMIT, Map.of(
                "sessionToken", TOKEN, "productId", "4", "itemCount", "2",
                "item.0", RowCodec.encode("9", "", "true"),
                "item.1", RowCodec.encode("", "upload-2", "false")));
    }

    @Test
    void adminProductAndOrderRequestsNeverAcceptOperatorIdentity() throws Exception {
        RecordingClient client = new RecordingClient();
        ShopGateway gateway = new SocketShopGateway(client, TOKEN);

        client.reply(pageResponse());
        gateway.adminSearchProducts("", ShopCategory.OTHER, false,
                ShopProductSort.NEWEST, 1);
        assertRequest(client, Actions.SHOP_PRODUCT_SEARCH, Map.of(
                "sessionToken", TOKEN, "keyword", "", "category", "OTHER",
                "enabled", "false", "sort", "NEWEST", "page", "1"));

        client.reply(ResponseMessage.success("reply", "ok", Map.of("productId", "4")));
        gateway.saveProduct(new ShopData.ProductInput(4L, "商品", "说明",
                ShopCategory.DAILY_SUPPLIES, new BigDecimal("10.00"), true));
        assertRequest(client, Actions.SHOP_ADMIN_PRODUCT_SAVE, Map.of(
                "sessionToken", TOKEN, "productId", "4", "name", "商品", "description", "说明",
                "category", "DAILY_SUPPLIES", "price", "10.00", "enabled", "true"));

        client.reply(orderPageResponse());
        gateway.adminOrders("buyer", ShopOrderStatus.PAID, 2);
        assertRequest(client, Actions.SHOP_ADMIN_ORDER_SEARCH, Map.of(
                "sessionToken", TOKEN, "keyword", "buyer", "status", "PAID", "page", "2"));
    }

    @Test
    void mutationsAndBankBalanceUseExactProtocolActions() throws Exception {
        RecordingClient client = new RecordingClient();
        ShopGateway gateway = new SocketShopGateway(client, TOKEN);

        client.reply(cartResponse());
        gateway.setCartQuantity(3L, 4);
        assertRequest(client, Actions.SHOP_CART_SET_QUANTITY, Map.of(
                "sessionToken", TOKEN, "productId", "3", "quantity", "4"));

        client.reply(ResponseMessage.success("reply", "ok", Map.of("changed", "true")));
        gateway.setProductEnabled(3L, false);
        assertRequest(client, Actions.SHOP_ADMIN_PRODUCT_SET_ENABLED, Map.of(
                "sessionToken", TOKEN, "productId", "3", "enabled", "false"));

        client.reply(ResponseMessage.success("reply", "ok", Map.of(
                "productId", "3", "stockAfter", "12")));
        gateway.adjustInventory(3L, -2, "盘点");
        assertRequest(client, Actions.SHOP_ADMIN_INVENTORY_ADJUST, Map.of(
                "sessionToken", TOKEN, "productId", "3", "delta", "-2", "reason", "盘点"));

        client.reply(ResponseMessage.success("reply", "ok", Map.of(
                "accountId", "1", "username", "student", "displayName", "学生",
                "balance", "88.20", "status", "ACTIVE", "updatedAt", "2026-09-01T08:00:00Z")));
        assertEquals(new BigDecimal("88.20"), gateway.bankBalance());
        assertRequest(client, Actions.BANK_ACCOUNT_GET, Map.of("sessionToken", TOKEN));
    }

    @Test
    void serverFailureIsPropagatedWithoutASecondRequest() {
        RecordingClient client = new RecordingClient();
        client.reply(ResponseMessage.failure("reply", "登录已过期"));
        ShopGateway gateway = new SocketShopGateway(client, TOKEN);

        IOException failure = assertThrows(IOException.class, gateway::cart);

        assertEquals("登录已过期", failure.getMessage());
        assertEquals(1, client.requests().size());
    }

    private static void assertRequest(RecordingClient client, String action,
                                      Map<String, String> parameters) {
        assertEquals(action, client.lastRequest().action());
        assertEquals(parameters, client.lastRequest().parameters());
    }

    private static ResponseMessage pageResponse() {
        return ResponseMessage.success("reply", "ok", Map.of(
                "page", "1", "pageSize", "10", "total", "0", "count", "0"));
    }

    private static ResponseMessage orderPageResponse() {
        return ResponseMessage.success("reply", "ok", Map.of(
                "page", "2", "pageSize", "10", "total", "0", "count", "0"));
    }

    private static ResponseMessage receiptResponse() {
        return ResponseMessage.success("reply", "ok", Map.of(
                "orderId", "7", "orderNo", "ORD-7", "totalAmount", "24.60",
                "status", "PAID", "duplicate", "false"));
    }

    private static ResponseMessage cartResponse() {
        return ResponseMessage.success("reply", "ok", Map.of(
                "count", "0", "estimatedTotal", "0.00"));
    }

    private static ResponseMessage emptyResponse() {
        return ResponseMessage.success("reply", "ok", Map.of());
    }

    private static final class RecordingClient extends VCampusClient {
        private final List<RequestMessage> requests = new java.util.ArrayList<>();
        private ResponseMessage response = emptyResponse();

        private RecordingClient() {
            super("recording.invalid", 1);
        }

        @Override
        public ResponseMessage send(RequestMessage request) {
            requests.add(request);
            return response;
        }

        void reply(ResponseMessage value) {
            response = value;
        }

        RequestMessage lastRequest() {
            return requests.getLast();
        }

        List<RequestMessage> requests() {
            return List.copyOf(requests);
        }
    }
}
