package com.vcampus.server.service;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import com.vcampus.server.database.LibraryPaperStore;
import com.vcampus.server.paper.PaperFileStore;
import com.vcampus.server.security.SessionManager;
import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.*;
import static com.vcampus.common.protocol.Actions.*;

public final class LibraryPaperService implements AutoCloseable {
    private static final int PAGE_SIZE=8;
    private final LibraryPaperStore papers;private final PaperFileStore files;private final SessionManager sessions;
    public LibraryPaperService(LibraryPaperStore papers,PaperFileStore files,SessionManager sessions){this.papers=papers;this.files=files;this.sessions=sessions;}
    public ResponseMessage handle(RequestMessage r) {
        var user=sessions.find(r.parameters().get("sessionToken"));
        if(user.isEmpty())return fail(r,"登录已过期，请重新登录");
        var s=user.get();boolean manage=LibraryAccessPolicy.canManage(s.roles());
        if(s.forcePasswordChange())return fail(r,"请先修改初始密码");
        if(!manage && !LibraryAccessPolicy.canBorrow(s.roles()))return fail(r,"没有论文查询权限");
        if(!Set.of(LIBRARY_PAPER_SEARCH,LIBRARY_PAPER_GET,LIBRARY_PAPER_READ).contains(r.action()) && !manage)return fail(r,"只有图书管理员可以维护论文");
        try {
            Map<String,String> d=new LinkedHashMap<>();String message="操作成功";
            switch(r.action()) {
                case LIBRARY_PAPER_SEARCH -> {
                    String year=year(p(r,"year"));int page=number(p(r,"page").isBlank()?"1":p(r,"page"),1,1000000);
                    var result=papers.search(text(r,"keyword",300,false),year,page,PAGE_SIZE,manage&&bool(r,"includeDisabled",false));
                    d.put("page",""+result.page());d.put("pageSize",""+PAGE_SIZE);d.put("total",""+result.total());d.put("count",""+result.rows().size());
                    for(int i=0;i<result.rows().size();i++)d.put("row."+i,result.rows().get(i).encode());
                }
                case LIBRARY_PAPER_GET -> d.put("row",visible(id(r),manage).encode());
                case LIBRARY_PAPER_SAVE -> {
                    long id=p(r,"paperId").isBlank()?0:Long.parseLong(p(r,"paperId"));if(id<0)throw new IllegalArgumentException("论文编号无效");
                    String url=text(r,"sourceUrl",2000,false);if(!url.isBlank())validateUrl(url);
                    String rights=text(r,"rights",2000,false);
                    if(url.isBlank()&&rights.isBlank())throw new IllegalArgumentException("请填写原文网址或来源与分享说明");
                    String doi=text(r,"doi",255,false).toLowerCase(Locale.ROOT);
                    if(!doi.isBlank() && !doi.matches("10\\.\\d{4,9}/\\S+"))throw new IllegalArgumentException("DOI 应形如 10.1234/example");
                    var metadata=new LibraryPaperStore.Metadata(text(r,"title",300,true),text(r,"authors",300,true),text(r,"abstract",10000,false),text(r,"keywords",500,false),year(p(r,"year")),text(r,"venue",300,false),doi,url,rights);
                    d.put("paperId",""+papers.save(id,metadata,s.userId()));message=id==0?"论文已保存为未上架，请上传 PDF 后按需上架":"论文信息已保存";
                }
                case LIBRARY_PAPER_SET_ENABLED -> {papers.setEnabled(id(r),bool(r,"enabled",null),s.userId());message="上架状态已更新";}
                case LIBRARY_PAPER_UPLOAD_START -> {
                    var paper=visible(id(r),true);if(paper.rights().isBlank())throw new IllegalArgumentException("请先填写来源与分享说明，再上传 PDF");
                    String ticket=files.start(s.userId(),paper.id(),text(r,"filename",180,true),Long.parseLong(p(r,"byteSize")),paper.sha256());
                    d.put("uploadId",ticket);d.put("chunkBytes",""+PaperFileStore.CHUNK_BYTES);
                }
                case LIBRARY_PAPER_UPLOAD_CHUNK -> {
                    String base64=p(r,"contentBase64");if(base64.length()>4*((PaperFileStore.CHUNK_BYTES+2)/3))throw new IllegalArgumentException("PDF 分块过大");
                    files.append(s.userId(),p(r,"uploadId"),number(p(r,"chunkIndex"),0,100000),Base64.getDecoder().decode(base64));
                }
                case LIBRARY_PAPER_UPLOAD_COMPLETE -> {
                    var pdf=files.complete(s.userId(),p(r,"uploadId"));
                    papers.attach(pdf.paperId(),pdf.filename(),pdf.byteSize(),pdf.sha256(),pdf.expectedHash(),s.userId());
                    files.consume(s.userId(),p(r,"uploadId"));d.put("sha256",pdf.sha256());message="PDF 上传完成";
                }
                case LIBRARY_PAPER_UPLOAD_CANCEL -> files.cancel(s.userId(),p(r,"uploadId"));
                case LIBRARY_PAPER_READ -> {
                    var paper=visible(id(r),manage);if(!paper.hasPdf())throw new IllegalArgumentException("该论文尚未收录 PDF，可查看原文网址");
                    if(!p(r,"sha256").isBlank()&&!p(r,"sha256").equals(paper.sha256()))throw new IllegalArgumentException("PDF 已更新，请刷新论文后重新下载");
                    int index=number(p(r,"chunkIndex"),0,100000);long offset=(long)index*PaperFileStore.CHUNK_BYTES;
                    if(offset>=paper.byteSize())throw new IllegalArgumentException("PDF 分块超出范围");
                    byte[] bytes=files.read(paper.sha256(),offset,PaperFileStore.CHUNK_BYTES);
                    if(bytes.length!=Math.min(PaperFileStore.CHUNK_BYTES,paper.byteSize()-offset))throw new IOException("PDF size mismatch");
                    d.put("contentBase64",Base64.getEncoder().encodeToString(bytes));d.put("chunkIndex",""+index);d.put("totalChunks",""+Math.ceilDiv(paper.byteSize(),PaperFileStore.CHUNK_BYTES));
                    d.put("totalBytes",""+paper.byteSize());d.put("sha256",paper.sha256());d.put("filename",paper.filename());
                }
                default -> {return fail(r,"不支持的论文操作");}
            }
            return ResponseMessage.success(r.requestId(),message,d);
        }catch(IllegalArgumentException e){return fail(r,e instanceof NumberFormatException?"数字参数无效":Objects.requireNonNullElse(e.getMessage(),"参数无效"));}
        catch(SQLException e){return fail(r,e.getErrorCode()==1062?"DOI 或 PDF 已存在，请查询已有论文记录":"论文数据暂不可用，请稍后重试");}
        catch(IOException e){return fail(r,"PDF 文件暂不可用，请重试或联系图书管理员");}
    }
    private LibraryPaper visible(long id,boolean manage)throws SQLException{
        var paper=papers.find(id).orElseThrow(()->new IllegalArgumentException("论文不存在或已下架"));
        if(!manage&&!paper.enabled())throw new IllegalArgumentException("论文不存在或已下架");return paper;
    }
    private static String p(RequestMessage r,String k){return r.parameters().getOrDefault(k,"").trim();}
    private static long id(RequestMessage r){long id=Long.parseLong(p(r,"paperId"));if(id<1)throw new IllegalArgumentException("论文编号无效");return id;}
    private static int number(String v,int min,int max){int n=Integer.parseInt(v);if(n<min||n>max)throw new IllegalArgumentException("数字参数超出范围");return n;}
    private static String text(RequestMessage r,String k,int max,boolean required){String v=p(r,k);if(v.length()>max||required&&v.isBlank())throw new IllegalArgumentException(k+" 不能为空或超过 "+max+" 字");return v;}
    private static String year(String y){if(!y.isBlank()&&!y.matches("[12][0-9]{3}"))throw new IllegalArgumentException("年份须为四位数字（1000–2999）");return y;}
    private static boolean bool(RequestMessage r,String k,Boolean fallback){String v=p(r,k);if(v.isBlank()&&fallback!=null)return fallback;if(!Set.of("true","false").contains(v))throw new IllegalArgumentException("状态参数无效");return Boolean.parseBoolean(v);}
    private static void validateUrl(String url){URI u=URI.create(url);if(!Set.of("http","https").contains(Objects.requireNonNullElse(u.getScheme(),"").toLowerCase(Locale.ROOT))||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException("原文网址须为 HTTP 或 HTTPS 链接");}
    private static ResponseMessage fail(RequestMessage r,String message){return ResponseMessage.failure(r.requestId(),message);}
    @Override public void close(){files.close();}
}
