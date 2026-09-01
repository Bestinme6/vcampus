package com.vcampus.client.fx.library;

import com.vcampus.client.ui.LibraryViewData;
import com.vcampus.common.protocol.*;
import java.time.Instant;
import java.util.*;

public final class LibraryData {
    private LibraryData() {}
    public record Book(LibraryViewData.CatalogRow book,int onLoanCopies,long borrowCount) {}
    public record Page<T>(List<T> rows,int page,int pageSize,int total) {
        public Page {rows=List.copyOf(rows);if(page<1||pageSize<1||total<0||rows.size()>pageSize||rows.size()>total)throw new IllegalArgumentException("分页数据无效");}
        public int pages(){return Math.max(1,(int)((total+(long)pageSize-1)/pageSize));}
    }
    public record Reservation(long id,long bookId,String catalogCode,String title,String status,Instant createdAt,Instant notifiedAt) {}
    public static Page<Book> books(ResponseMessage response) {
        require(response);
        var legacy=LibraryViewData.catalogPage(response); var rows=new ArrayList<Book>();
        for(int i=0;i<legacy.rows().size();i++) rows.add(book(legacy.rows().get(i),response.data(),"row."+i+"."));
        return new Page<>(rows,legacy.page(),legacy.pageSize(),legacy.total());
    }
    public static Book detail(ResponseMessage response) {
        require(response); var data=new HashMap<>(response.data());
        data.put("row.0",Objects.requireNonNull(data.get("row"),"缺少书目详情"));
        data.putAll(Map.of("page","1","pageSize","1","total","1","count","1"));
        var legacy=LibraryViewData.catalogPage(ResponseMessage.success(response.requestId(),response.message(),data));
        return book(legacy.rows().getFirst(),response.data(),"");
    }
    private static Book book(LibraryViewData.CatalogRow row,Map<String,String> data,String prefix) {
        long loans=metadata(data,prefix+"onLoanCopies"), count=metadata(data,prefix+"borrowCount");
        if(loans>Integer.MAX_VALUE||loans>row.totalCopies()||row.availableCopies()<0||row.availableCopies()>row.totalCopies())
            throw new IllegalArgumentException("馆藏统计数据无效");
        return new Book(row,(int)loans,count);
    }
    private static long metadata(Map<String,String> data,String key) {
        if(!data.containsKey(key))return -1;
        long value=Long.parseLong(data.get(key));if(value<0)throw new IllegalArgumentException("馆藏统计数据无效");return value;
    }
    public static Page<Reservation> reservations(ResponseMessage response) {
        require(response); var d=response.data();int count=Integer.parseInt(d.get("count"));
        if(count<0||count>100)throw new IllegalArgumentException("预约数据无效");
        var rows=new ArrayList<Reservation>();
        for(int i=0;i<count;i++) {
            var f=RowCodec.decode(Objects.requireNonNull(d.get("row."+i)));
            if(f.size()!=7||!Set.of("WAITING","NOTIFIED","CANCELLED").contains(f.get(4)))throw new IllegalArgumentException("预约数据无效");
            long id=Long.parseLong(f.get(0)),bookId=Long.parseLong(f.get(1));
            if(id<1||bookId<1)throw new IllegalArgumentException("预约编号无效");
            rows.add(new Reservation(id,bookId,f.get(2),f.get(3),f.get(4),Instant.parse(f.get(5)),f.get(6).isBlank()?null:Instant.parse(f.get(6))));
        }
        return new Page<>(rows,Integer.parseInt(d.get("page")),Integer.parseInt(d.get("pageSize")),Integer.parseInt(d.get("total")));
    }
    public static void require(ResponseMessage response) {
        if(response==null)throw new IllegalArgumentException("服务器未返回数据");
        if(!response.success())throw new IllegalArgumentException(response.message());
    }
}
