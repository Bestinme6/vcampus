package com.vcampus.client.fx.library;

import com.vcampus.common.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LibraryDataTest {
    @Test void onLoanIsIndependentOfOtherUnavailableCopies() {
        var page=LibraryData.books(ResponseMessage.success("id","ok",Map.of(
                "page","1","pageSize","10","total","1","count","1","row.0",bookRow(),
                "row.0.onLoanCopies","1","row.0.borrowCount","24")));
        assertEquals(1,page.rows().getFirst().onLoanCopies());
        assertEquals(24,page.rows().getFirst().borrowCount());
        assertEquals(4,page.rows().getFirst().book().totalCopies());
    }
    @Test void legacyMetadataIsUnknownInsteadOfGuessingLoansFromUnavailableCopies() {
        var page=LibraryData.books(ResponseMessage.success("id","ok",Map.of(
                "page","1","pageSize","10","total","1","count","1","row.0",bookRow())));
        assertEquals(-1,page.rows().getFirst().onLoanCopies());
        assertEquals(-1,page.rows().getFirst().borrowCount());
    }
    @Test void malformedReservationStateIsRejected() {
        var response=ResponseMessage.success("id","ok",Map.of("page","1","pageSize","10","total","1","count","1",
                "row.0",RowCodec.encode("1","7","BK000000007","测试图书","UNKNOWN","2026-08-31T00:00:00Z","")));
        assertThrows(IllegalArgumentException.class,()->LibraryData.reservations(response));
    }
    @Test void serverFailureRetainsItsExplanation() {
        var exception=assertThrows(IllegalArgumentException.class,()->LibraryData.books(ResponseMessage.failure("id","请重新登录")));
        assertEquals("请重新登录",exception.getMessage());
    }
    private String bookRow() {
        return RowCodec.encode("7","BK000000007","","程序设计","示例作者","示例出版社","2026","计算机","内容介绍","true","4","1");
    }
}
