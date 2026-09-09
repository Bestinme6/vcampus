package com.vcampus.client.fx.identity;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SocketIdentityGatewayTest {
    @Test void forwardsStudentAccountAndTeacherArguments() throws Exception {
        RecordingClient client=new RecordingClient();
        StudentGateway students=new SocketStudentGateway(client,"token");
        students.search("林", StudentStatus.ENROLLED,2);
        assertEquals(List.of("token","林","ENROLLED","2"),client.studentSearchArgs);
        students.updateContact(8,"139","s@v.edu","1号楼");
        assertEquals("8",client.studentContact.get("studentId"));

        TeacherGateway teacher=new SocketTeacherGateway(client,"token");
        teacher.updateContact("138","t@v.edu");
        assertEquals(List.of("token","138","t@v.edu"),client.teacherArgs);

        AccountGateway accounts=new SocketAccountGateway(client,"token");
        accounts.search("T",UserRole.TEACHER,false,3);
        assertEquals(List.of("token","T","TEACHER","false","3"),client.accountSearchArgs);
        char[] password="temporary".toCharArray();
        accounts.resetPassword(31,password);
        assertEquals("temporary",client.capturedPassword);
        assertEquals("temporary",new String(password));
    }

    @Test void failedResponseBecomesReadableException() {
        RecordingClient client=new RecordingClient(); client.failure=true;
        IllegalStateException error=assertThrows(IllegalStateException.class,
                ()->new SocketTeacherGateway(client,"token").self());
        assertEquals("无权限",error.getMessage());
    }

    private static final class RecordingClient extends VCampusClient {
        List<String> studentSearchArgs=List.of(); Map<String,String> studentContact=Map.of();
        List<String> teacherArgs=List.of(); List<String> accountSearchArgs=List.of(); String capturedPassword="";
        boolean failure;
        RecordingClient(){super("localhost",1);}
        private ResponseMessage ok(Map<String,String> data){return failure?ResponseMessage.failure("r","无权限"):ResponseMessage.success("r","ok",data);}
        @Override public ResponseMessage searchStudents(String token,String keyword,String status,int page){
            studentSearchArgs=List.of(token,keyword,status,Integer.toString(page));
            Map<String,String> data=new LinkedHashMap<>(Map.of("page","2","pageSize","8","total","1","count","1"));
            putStudent(data,"row.0."); return ok(data);
        }
        @Override public ResponseMessage updateStudentContact(String token,Map<String,String> values){studentContact=Map.copyOf(values);return ok(Map.of());}
        @Override public ResponseMessage getMyTeacherProfile(String token){return ok(Map.of("teacherNumber","T1","fullName","李老师","departmentName","计算机学院","professionalTitle","讲师"));}
        @Override public ResponseMessage updateTeacherContact(String token,String phone,String email){teacherArgs=List.of(token,phone,email);return ok(Map.of());}
        @Override public ResponseMessage searchAccounts(String token,String keyword,String identity,String enabled,int page){
            accountSearchArgs=List.of(token,keyword,identity,enabled,Integer.toString(page));
            return ok(Map.of("page","3","pageSize","8","total","1","count","1","row.0",RowCodec.encode("31","T1","李老师","TEACHER","","true","false","")));
        }
        @Override public ResponseMessage resetAccountPassword(String token,long userId,char[] password){capturedPassword=new String(Arrays.copyOf(password,password.length));return ok(Map.of());}
        private static void putStudent(Map<String,String> data,String p){
            data.put(p+"id","8");data.put(p+"studentNumber","2026000008");data.put(p+"fullName","林同学");data.put(p+"gender","FEMALE");
            data.put(p+"departmentName","计算机学院");data.put(p+"majorName","软件工程");data.put(p+"className","软工2601");data.put(p+"enrollmentYear","2026");
            data.put(p+"status","ENROLLED");data.put(p+"phone","");data.put(p+"email","");
        }
    }
}
