package com.vcampus.client.fx.library;

import com.vcampus.common.protocol.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LibraryDataTest {
    @Test void onLoanIsIndependentOfOtherUnavailableCopies() {
        var page=LibraryData.books(ResponseMessage.success("id","ok",Map.of(
                "page","1","pageSize","10","total","1","count","1","row.0",bookRow(),
                "row.0.onLoanCopies","1","row.0.borrowCount","24","row.0.replacementPrice","89.00")));
        assertEquals(1,page.rows().getFirst().onLoanCopies());
        assertEquals(24,page.rows().getFirst().borrowCount());
        assertEquals(4,page.rows().getFirst().book().totalCopies());
        assertEquals(new BigDecimal("89.00"),page.rows().getFirst().replacementPrice());
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
    @Test void feeRowsAndDetailsAreStrictlyDecoded(){String row=RowCodec.encode("1","LFB000000001","7","Java","B000000001","PENDING","0.50","17.80","0.00","18.30","2026-09-03T00:00:00Z","","","");var page=LibraryData.fees(ResponseMessage.success("id","ok",Map.of("page","1","pageSize","10","total","1","count","1","row.0",row)));assertEquals(new BigDecimal("18.30"),page.rows().getFirst().totalAmount());var detail=LibraryData.feeDetail(ResponseMessage.success("id","ok",Map.of("row",row,"overdueDays","2","billableDays","1","returnCondition","DAMAGED","damageLevel","MINOR","replacementPrice","89.00","reason","破损","paymentReference","","refundReference","")));assertEquals(new BigDecimal("89.00"),detail.replacementPrice());}
    @Test void feeDecoderRejectsWrongWidthAndSum(){assertThrows(IllegalArgumentException.class,()->LibraryData.fees(ResponseMessage.success("id","ok",Map.of("page","1","pageSize","10","total","1","count","1","row.0",RowCodec.encode("1","short")))));String row=RowCodec.encode("1","LFB000000001","7","Java","B000000001","PENDING","0.50","1.00","0.00","9.00","2026-09-03T00:00:00Z","","","");assertThrows(IllegalArgumentException.class,()->LibraryData.fees(ResponseMessage.success("id","ok",Map.of("page","1","pageSize","10","total","1","count","1","row.0",row))));}
    private String bookRow() {
        return RowCodec.encode("7","BK000000007","","程序设计","示例作者","示例出版社","2026","计算机","内容介绍","true","4","1");
    }
}
