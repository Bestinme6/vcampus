package com.vcampus.client.fx.identity;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StudentDataTest {
    @Test void parsesProfilePageHistoryAndReferences() {
        Map<String,String> values = profileValues();
        StudentData.Profile profile = StudentData.profile(success(values));
        assertEquals(8L, profile.id());
        assertEquals(Gender.FEMALE, profile.gender());
        assertEquals(StudentStatus.ENROLLED, profile.status());

        Map<String,String> page = new LinkedHashMap<>();
        page.put("page","1"); page.put("pageSize","8"); page.put("total","1"); page.put("count","1");
        profileValues().forEach((key,value)->page.put("row.0."+key,value));
        assertEquals("林同学", StudentData.page(success(page)).rows().getFirst().fullName());

        Map<String,String> history = new LinkedHashMap<>();
        history.put("count","1"); history.put("row.0.oldStatus",""); history.put("row.0.newStatus","ENROLLED");
        history.put("row.0.reason","建档"); history.put("row.0.operator","系统"); history.put("row.0.changedAt","2026-09-09T08:00:00Z");
        assertEquals("", StudentData.history(success(history)).getFirst().oldStatus());

        Map<String,String> refs = new LinkedHashMap<>();
        putRef(refs,"department",0,"10","0","CS","计算机学院","0");
        putRef(refs,"major",0,"20","10","SE","软件工程","0");
        putRef(refs,"class",0,"30","20","SE2601","软工2601","2026");
        refs.put("department.count","1"); refs.put("major.count","1"); refs.put("class.count","1");
        assertEquals(2026, StudentData.references(success(refs)).classes().getFirst().year());
    }

    @Test void malformedProfileHasReadableMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StudentData.profile(success(Map.of("id","bad"))));
        assertEquals("服务器返回的学籍数据格式不正确", error.getMessage());
    }

    private static Map<String,String> profileValues() {
        Map<String,String> data = new LinkedHashMap<>();
        data.put("id","8"); data.put("studentNumber","2026000008"); data.put("fullName","林同学");
        data.put("gender","FEMALE"); data.put("birthDate","2008-05-01"); data.put("departmentId","10");
        data.put("departmentName","计算机学院"); data.put("majorId","20"); data.put("majorName","软件工程");
        data.put("classId","30"); data.put("className","软工2601"); data.put("enrollmentYear","2026");
        data.put("status","ENROLLED"); data.put("phone",""); data.put("email","student@vcampus.edu"); data.put("address","");
        return data;
    }

    private static void putRef(Map<String,String> data,String type,int index,String id,String parentId,String code,String name,String year) {
        String p=type+"."+index+".";
        data.put(p+"id",id); data.put(p+"parentId",parentId); data.put(p+"code",code); data.put(p+"name",name); data.put(p+"year",year);
    }

    private static ResponseMessage success(Map<String,String> data) { return ResponseMessage.success("r","ok",data); }
}
