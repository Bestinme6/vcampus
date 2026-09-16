package com.vcampus.server.service;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.server.database.SecondhandOrderStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondhandOrderServiceTest {
    private FakeOrders store;
    private SecondhandOrderService service;
    private String buyer;
    private String ordinary;
    private String admin;

    @BeforeEach
    void setUp() {
        store = new FakeOrders();
        SessionManager sessions = new SessionManager();
        buyer = token(sessions, 2L, Set.of(UserRole.STUDENT));
        ordinary = token(sessions, 3L, Set.of(UserRole.STUDENT));
        admin = token(sessions, 9L, Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN));
        service = new SecondhandOrderService(store, sessions);
    }

    @Test
    void buyUsesSessionBuyerAndParsesSharedInlineAddress() {
        Map<String, String> values = new LinkedHashMap<>(Map.of(
                "itemId", "7", "operationId", UUID.randomUUID().toString(),
                "addressMode", "inline", "recipient", "测试买家",
                "phone", "13800000000", "address", "江苏省南京市测试路1号"));
        values.put("buyerId", "999");

        var response = service.buy(request(Actions.SHOP_USED_ORDER_BUY, buyer, values));

        assertTrue(response.success());
        assertEquals(2L, store.actor);
        assertEquals("测试买家", store.address.inline().recipient());
    }

    @Test
    void ordinaryUserCannotUseAdministrativeSearchOrResolution() {
        assertFalse(service.searchAdmin(request(Actions.SHOP_USED_ADMIN_ORDER_SEARCH, ordinary,
                Map.of("page", "1"))).success());
        assertFalse(service.resolve(request(Actions.SHOP_USED_ADMIN_ORDER_RESOLVE, ordinary,
                Map.of("orderId", "10", "resolution", "REFUND",
                        "operationId", UUID.randomUUID().toString(), "reason", "买卖双方协商退款"))).success());
        assertTrue(service.resolve(request(Actions.SHOP_USED_ADMIN_ORDER_RESOLVE, admin,
                Map.of("orderId", "10", "resolution", "REFUND",
                        "operationId", UUID.randomUUID().toString(), "reason", "买卖双方协商退款"))).success());
        assertEquals(9L, store.actor);
    }

    private static RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private static String token(SessionManager sessions, long id, Set<UserRole> roles) {
        return sessions.create(new UserAccount(id, "user" + id, "h", "s", "用户" + id,
                true, false, roles)).token();
    }

    private static final class FakeOrders implements SecondhandOrderStore {
        long actor;
        ShippingAddressChoice address;

        @Override public BuyResult buy(long buyerId, long itemId, String operationId,
                                       ShippingAddressChoice address) {
            actor = buyerId; this.address = address;
            return new BuyResult(10L, "USED-10", ShopOrderStatus.PAID, false);
        }
        @Override public OrderDetail order(long requesterId,long orderId,boolean admin){actor=requesterId;return detail();}
        @Override public OrderPage search(long requesterId,boolean selling,int page,int pageSize){actor=requesterId;return new OrderPage(List.of(detail()),page,pageSize,1);}
        @Override public OrderPage searchAdmin(String keyword,ShopOrderStatus status,int page,int pageSize){return new OrderPage(List.of(detail()),page,pageSize,1);}
        @Override public OrderDetail cancel(long buyerId,long orderId){actor=buyerId;return detail();}
        @Override public OrderDetail ship(long sellerId,long orderId){actor=sellerId;return detail();}
        @Override public OrderDetail confirm(long buyerId,long orderId){actor=buyerId;return detail();}
        @Override public OrderDetail resolve(long adminId,long orderId,Resolution resolution,String operationId,String reason){actor=adminId;return detail();}
        private OrderDetail detail(){return new OrderDetail(10L,"USED-10",7L,2L,1L,
                new BigDecimal("12.00"),ShopOrderStatus.PAID,EscrowStatus.HELD,"旧教材","BOOK","九成新","卖家",
                new ShippingAddressValue("买家","13800000000","江苏省南京市测试路1号"),Instant.now(),null,null,null);}
    }
}
