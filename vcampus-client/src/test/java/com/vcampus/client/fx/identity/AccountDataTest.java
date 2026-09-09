package com.vcampus.client.fx.identity;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountDataTest {
    @Test void parsesRowsAndReferenceHierarchy() {
        Map<String,String> data=new LinkedHashMap<>();
        data.put("page","1"); data.put("pageSize","8"); data.put("total","1"); data.put("count","1");
        data.put("row.0", RowCodec.encode("31","2026000031","学生甲","STUDENT","FORUM_ADMIN,LIBRARY_ADMIN","true","false",""));
        AccountData.Account account=AccountData.page(ResponseMessage.success("r","ok",data)).rows().getFirst();
        assertEquals(UserRole.STUDENT,account.baseIdentity());
        assertEquals(Set.of(UserRole.FORUM_ADMIN,UserRole.LIBRARY_ADMIN),account.administrativeRoles());

        Map<String,String> refs=new LinkedHashMap<>();
        putRef(refs,"department",0,"10","0","CS","计算机学院","0");
        putRef(refs,"major",0,"20","10","SE","软件工程","0");
        putRef(refs,"class",0,"30","20","SE2601","软工2601","2026");
        refs.put("department.count","1"); refs.put("major.count","1"); refs.put("class.count","1");
        assertEquals(20L,AccountData.references(ResponseMessage.success("r","ok",refs)).classes().getFirst().parentId());
    }

    @Test void rejectsInvalidBoolean() {
        Map<String,String> data=Map.of("page","1","pageSize","8","total","1","count","1",
                "row.0",RowCodec.encode("31","u","n","STUDENT","","perhaps","false",""));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
                ()->AccountData.page(ResponseMessage.success("r","ok",data)));
        assertEquals("服务器返回的账号数据格式不正确",error.getMessage());
    }

    private static void putRef(Map<String,String> data,String type,int index,String id,String parentId,String code,String name,String year) {
        String p=type+"."+index+".";
        data.put(p+"id",id); data.put(p+"parentId",parentId); data.put(p+"code",code); data.put(p+"name",name); data.put(p+"year",year);
    }
}
