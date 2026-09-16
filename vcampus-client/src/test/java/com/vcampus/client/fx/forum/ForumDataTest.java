package com.vcampus.client.fx.forum;

import com.vcampus.common.model.ForumBoostTier;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ForumDataTest {
    @Test void feedParsesCompositeHeatAndBoostSummary() {
        Map<String,String> data=feedData();
        data.put("boost.0",RowCodec.encode("2","75"));
        data.put("heat.0","123");
        var current=ForumData.feed(ResponseMessage.success("x","ok",data));
        assertEquals(new ForumData.BoostSummary(2,75),current.rows().getFirst().boost());
        assertEquals(123,current.rows().getFirst().heat());
    }

    @Test void boostStateAndReceiptDecodeEveryServerOwnedField() {
        var state=ForumData.boostState(ResponseMessage.success("x","ok",Map.of(
                "eligible","true","reason","","activeBoostCount","2","activeHeat","75",
                "userOperationsUsed","1","userAmountUsed","5.00","postAmountUsed","35.00",
                "balance","45.00","availableTiers","FIVE,TEN")));
        assertAll(
                ()->assertTrue(state.eligible()),
                ()->assertEquals(2,state.activeBoostCount()),
                ()->assertEquals(new BigDecimal("5.00"),state.userAmountUsed()),
                ()->assertEquals(List.of(ForumBoostTier.FIVE,ForumBoostTier.TEN),state.availableTiers()));

        var receipt=ForumData.boostReceipt(ResponseMessage.success("x","ok",Map.of(
                "boostId","31","bankLedgerId","41","bankReference","FB-test",
                "amount","10.00","initialHeat","100","balanceAfter","40.00",
                "activeBoostCount","3","activeHeat","175","duplicate","true")));
        assertAll(
                ()->assertEquals(31,receipt.boostId()),
                ()->assertEquals(41,receipt.bankLedgerId()),
                ()->assertEquals("FB-test",receipt.bankReference()),
                ()->assertEquals(new BigDecimal("10.00"),receipt.amount()),
                ()->assertEquals(100,receipt.initialHeat()),
                ()->assertEquals(new BigDecimal("40.00"),receipt.balanceAfter()),
                ()->assertEquals(3,receipt.activeBoostCount()),
                ()->assertEquals(175,receipt.activeHeat()),
                ()->assertTrue(receipt.duplicate()));
    }

    private Map<String,String> feedData() {
        Map<String,String> data=new LinkedHashMap<>();
        data.put("forumVersion","3");
        data.put("count","1");
        data.put("page","1");
        data.put("pageSize","20");
        data.put("total","1");
        data.put("row.0",RowCodec.encode("11","1","校园","2","同学","标题","摘要",
                "NORMAL","false","false","false","12","3","2026-09-15T01:00:00Z",""));
        data.put("engagement.0",RowCodec.encode("4","false","true","false"));
        return data;
    }

}
