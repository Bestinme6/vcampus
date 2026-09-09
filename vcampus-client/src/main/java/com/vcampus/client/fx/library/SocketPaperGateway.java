package com.vcampus.client.fx.library;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.LibraryPaper;
import com.vcampus.common.protocol.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.DoubleConsumer;
import static com.vcampus.common.protocol.Actions.*;

public final class SocketPaperGateway implements PaperGateway {
    private static final int CHUNK=192*1024;
    private static final long MAX=200L*1024*1024;
    @FunctionalInterface interface Exchange {ResponseMessage send(RequestMessage request)throws IOException;}
    private final Exchange exchange;private final String token;
    public SocketPaperGateway(VCampusClient client,String token){this(client::send,token);}
    SocketPaperGateway(Exchange exchange,String token){this.exchange=exchange;this.token=token;}
    private Map<String,String> call(String action,Map<String,String> values)throws IOException {
        cancelled();Map<String,String> p=new LinkedHashMap<>(values);p.put("sessionToken",token);
        var r=exchange.send(RequestMessage.create(action,p));if(!r.success())throw new IOException(r.message());return r.data();
    }
    @Override public Page search(String key,String year,int page,boolean hidden)throws IOException {
        var d=call(LIBRARY_PAPER_SEARCH,Map.of("keyword",key,"year",year,"page",""+page,"includeDisabled",""+hidden));
        try{int count=Integer.parseInt(d.get("count"));if(count<0||count>8)throw new IllegalArgumentException();List<LibraryPaper> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(LibraryPaper.decode(d.get("row."+i)));
            return new Page(rows,Integer.parseInt(d.get("page")),Integer.parseInt(d.get("pageSize")),Integer.parseInt(d.get("total")));
        }catch(RuntimeException e){throw new IOException("论文列表数据格式无效",e);}
    }
    @Override public LibraryPaper get(long id)throws IOException {
        var d=call(LIBRARY_PAPER_GET,Map.of("paperId",""+id));try{return LibraryPaper.decode(d.get("row"));}catch(RuntimeException e){throw new IOException("论文数据格式无效",e);}
    }
    @Override public long save(long id,Map<String,String> metadata)throws IOException {
        var p=new HashMap<>(metadata);p.put("paperId",""+id);var d=call(LIBRARY_PAPER_SAVE,p);try{return Long.parseLong(d.get("paperId"));}catch(RuntimeException e){throw new IOException("论文编号无效",e);}
    }
    @Override public void setEnabled(long id,boolean enabled)throws IOException{call(LIBRARY_PAPER_SET_ENABLED,Map.of("paperId",""+id,"enabled",""+enabled));}
    @Override public void upload(long id,Path source,DoubleConsumer progress)throws IOException {
        long size=Files.size(source);if(size<10||size>MAX)throw new IOException("请选择大小不超过 200 MiB 的 PDF；实际限额由服务器决定");
        var start=call(LIBRARY_PAPER_UPLOAD_START,Map.of("paperId",""+id,"filename",source.getFileName().toString(),"byteSize",""+size));
        String ticket=start.get("uploadId");boolean done=false;
        try{
            if(ticket==null||ticket.isBlank())throw new IOException("服务器未返回上传编号");
            int chunk=Integer.parseInt(start.get("chunkBytes"));if(chunk<1||chunk>CHUNK)throw new IOException("上传分块大小无效");
            MessageDigest digest=digest();long sent=0;int index=0;progress.accept(0);
            try(var in=Files.newInputStream(source)){
                while(sent<size){cancelled();byte[] bytes=in.readNBytes((int)Math.min(chunk,size-sent));if(bytes.length==0)throw new IOException("上传期间源文件发生变化，请重新选择");
                    digest.update(bytes);call(LIBRARY_PAPER_UPLOAD_CHUNK,Map.of("uploadId",ticket,"chunkIndex",""+index++,"contentBase64",Base64.getEncoder().encodeToString(bytes)));sent+=bytes.length;progress.accept((double)sent/size*0.95);}
                if(in.read()!=-1)throw new IOException("上传期间源文件发生变化，请重新选择");
            }
            var completed=call(LIBRARY_PAPER_UPLOAD_COMPLETE,Map.of("uploadId",ticket));
            if(!HexFormat.of().formatHex(digest.digest()).equals(completed.get("sha256")))throw new IOException("PDF 上传校验失败，请刷新记录检查后重试");
            done=true;progress.accept(1);
        }catch(IllegalArgumentException e){throw new IOException("PDF 上传响应无效",e);}
        finally{
            if(!done&&ticket!=null)try{exchange.send(RequestMessage.create(LIBRARY_PAPER_UPLOAD_CANCEL,Map.of("sessionToken",token,"uploadId",ticket)));}catch(IOException ignored){}
        }
    }
    @Override public void download(LibraryPaper paper,Path destination,DoubleConsumer progress)throws IOException {
        if(!paper.hasPdf()||paper.byteSize()>MAX||!paper.sha256().matches("[0-9a-f]{64}"))throw new IOException("论文没有可下载的 PDF 或文件信息无效");
        Path dest=destination.toAbsolutePath().normalize();Path temp=Files.createTempFile(dest.getParent(),".vcampus-paper-",".part");
        try{
            MessageDigest digest=digest();long received=0;int chunks=Math.toIntExact(Math.ceilDiv(paper.byteSize(),CHUNK));progress.accept(0);
            try(var out=Files.newOutputStream(temp)){
                for(int index=0;index<chunks;index++){
                    var d=call(LIBRARY_PAPER_READ,Map.of("paperId",""+paper.id(),"chunkIndex",""+index,"sha256",paper.sha256()));
                    if(!paper.sha256().equals(d.get("sha256"))||Long.parseLong(d.get("totalBytes"))!=paper.byteSize()||Integer.parseInt(d.get("totalChunks"))!=chunks||Integer.parseInt(d.get("chunkIndex"))!=index)
                        throw new IOException("下载期间 PDF 已变化，请刷新后重试");
                    byte[] bytes=Base64.getDecoder().decode(d.get("contentBase64"));
                    if(bytes.length!=Math.min(CHUNK,paper.byteSize()-received))throw new IOException("PDF 分块大小校验失败");
                    out.write(bytes);digest.update(bytes);received+=bytes.length;progress.accept((double)received/paper.byteSize()*0.98);
                }
            }
            if(received!=paper.byteSize()||!HexFormat.of().formatHex(digest.digest()).equals(paper.sha256()))throw new IOException("PDF 完整性校验失败，请重新下载");
            cancelled();
            try{Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING);}
            progress.accept(1);
        }catch(IllegalArgumentException e){throw new IOException("PDF 下载响应无效",e);}
        finally{Files.deleteIfExists(temp);}
    }
    private static void cancelled()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("传输已取消");}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
