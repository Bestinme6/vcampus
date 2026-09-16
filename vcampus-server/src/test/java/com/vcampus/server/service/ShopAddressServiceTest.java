package com.vcampus.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ShopAddressStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShopAddressServiceTest {
    private final SessionManager sessions = new SessionManager();
    private final RecordingStore store = new RecordingStore();
    private ShopAddressService service;
    private String userOneToken;
    private String userTwoToken;

    @BeforeEach
    void setUp() {
        userOneToken = sessions.create(account(1L, "one")).token();
        userTwoToken = sessions.create(account(2L, "two")).token();
        service = new ShopAddressService(store, sessions);
    }

    @Test
    void saveListAndDeleteAlwaysUseTheSessionOwner() {
        var saved = service.save(request(Actions.SHOP_ADDRESS_SAVE, userOneToken, Map.of(
                "recipient", "张三", "phone", "13800000000", "address", "校内一号楼 101")));

        assertTrue(saved.success(), saved.message());
        assertEquals(1L, store.lastUserId);
        assertEquals(List.of("1", "v1", "张三", "13800000000", "校内一号楼 101"),
                RowCodec.decode(saved.data().get("row")));

        store.rows = List.of(new ShopAddress(1, "v1",
                new ShippingAddressValue("张三", "13800000000", "校内一号楼 101")));
        var listed = service.list(request(Actions.SHOP_ADDRESS_LIST, userOneToken, Map.of()));
        assertTrue(listed.success(), listed.message());
        assertEquals("1", listed.data().get("count"));
        assertEquals(1L, store.lastUserId);

        var deleted = service.delete(request(Actions.SHOP_ADDRESS_DELETE, userTwoToken,
                Map.of("slot", "1")));
        assertTrue(deleted.success(), deleted.message());
        assertEquals(2L, store.lastUserId);
    }

    @Test
    void saveAcceptsOnlyAddressFieldsAndOptionalReplacementSlot() {
        var replaced = service.save(request(Actions.SHOP_ADDRESS_SAVE, userOneToken, Map.of(
                "recipient", "李四", "phone", "13900000000", "address", "校内二号楼 202",
                "replaceSlot", "2")));
        assertTrue(replaced.success(), replaced.message());
        assertEquals(2, store.lastReplaceSlot);

        List<Map<String, String>> invalid = List.of(
                Map.of("phone", "13900000000", "address", "校内二号楼 202"),
                Map.of("recipient", "李四", "address", "校内二号楼 202"),
                Map.of("recipient", "李四", "phone", "13900000000"),
                Map.of("recipient", "李四", "phone", "13900000000", "address", "校内二号楼 202",
                        "replaceSlot", "4"),
                Map.of("recipient", "李四", "phone", "13900000000", "address", "校内二号楼 202",
                        "userId", "2"));
        for (var values : invalid) assertFalse(service.save(
                request(Actions.SHOP_ADDRESS_SAVE, userOneToken, values)).success(), values.toString());
        assertEquals(1, store.saveCalls);
    }

    @Test
    void listDeleteAndExpiredSessionRejectUnexpectedOrUnauthenticatedRequests() {
        assertFalse(service.list(request(Actions.SHOP_ADDRESS_LIST, userOneToken,
                Map.of("userId", "2"))).success());
        assertFalse(service.delete(request(Actions.SHOP_ADDRESS_DELETE, userOneToken,
                Map.of("slot", "1", "phone", "13800000000"))).success());
        assertFalse(service.delete(request(Actions.SHOP_ADDRESS_DELETE, userOneToken,
                Map.of("slot", "0"))).success());
        assertEquals("登录已过期，请重新登录",
                service.list(RequestMessage.create(Actions.SHOP_ADDRESS_LIST, Map.of())).message());
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private UserAccount account(long id, String username) {
        return new UserAccount(id, username, "hash", "salt", username,
                true, false, Set.of(UserRole.STUDENT));
    }

    private static final class RecordingStore implements ShopAddressStore {
        private long lastUserId;
        private Integer lastReplaceSlot;
        private int saveCalls;
        private List<ShopAddress> rows = new ArrayList<>();

        @Override public List<ShopAddress> list(long userId) {
            lastUserId = userId;
            return rows;
        }

        @Override public ShopAddress save(long userId, Integer replaceSlot, ShippingAddressValue value) {
            lastUserId = userId;
            lastReplaceSlot = replaceSlot;
            saveCalls++;
            return new ShopAddress(replaceSlot == null ? 1 : replaceSlot, "v1", value);
        }

        @Override public boolean delete(long userId, int slot) {
            lastUserId = userId;
            return true;
        }
    }
}
