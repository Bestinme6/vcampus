package com.vcampus.client.ui;

import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryViewDataTest {
    @Test void parsesCatalogPageInServerFieldOrder(){Map<String,String>d=page();d.put("row.0",RowCodec.encode("10","9787111565277","Java 并发编程实战","Brian Goetz","机械工业出版社","2020","计算机","并发编程","true","4","2"));var p=LibraryViewData.catalogPage(ok(d));assertEquals("Java 并发编程实战",p.rows().getFirst().title());assertEquals(2,p.rows().getFirst().availableCopies());}
    @Test void parsesNullableLoanFieldsAndRules(){Map<String,String>d=page();d.put("maxLoans","5");d.put("initialLoanDays","30");d.put("renewalDays","15");d.put("row.0",RowCodec.encode("501","10","9787111565277","Java","101","B000000128","2026000001","张同学","2026-08-01T00:00:00Z","2026-08-31T00:00:00Z","0","","","SELF_SERVICE","true","false"));var p=LibraryViewData.loanPage(ok(d));assertTrue(p.rows().getFirst().overdue());assertNull(p.rows().getFirst().returnedAt());assertEquals(15,p.renewalDays());}
    @Test void rejectsMalformedRowsWithReadableMessage(){Map<String,String>d=page();d.put("row.0",RowCodec.encode("1"));var e=assertThrows(IllegalArgumentException.class,()->LibraryViewData.catalogPage(ok(d)));assertEquals("服务器返回的图书馆数据格式不正确",e.getMessage());}
    @Test void rejectsNegativePaginationMetadata(){Map<String,String>d=page();d.put("total","-1");d.put("row.0",RowCodec.encode("10","9787111565277","Java","作者","出版社","2020","计算机","简介","true","1","1"));assertThrows(IllegalArgumentException.class,()->LibraryViewData.catalogPage(ok(d)));}
    private Map<String,String> page(){Map<String,String>d=new LinkedHashMap<>();d.put("page","1");d.put("pageSize","10");d.put("total","1");d.put("count","1");return d;} private ResponseMessage ok(Map<String,String>d){return ResponseMessage.success("r","查询成功",d);}
}
