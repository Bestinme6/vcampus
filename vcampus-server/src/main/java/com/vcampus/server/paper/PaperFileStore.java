package com.vcampus.server.paper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded temporary uploads and immutable, content-addressed PDF storage. */
public final class PaperFileStore implements AutoCloseable {
    public static final int CHUNK_BYTES=192*1024;
    private static final Duration TTL=Duration.ofMinutes(30);
    private final Path root,pending;
    private final long maxBytes;
    private final Clock clock;
    private final Map<String,Upload> uploads=new HashMap<>();
    private final ScheduledExecutorService cleaner;
    public record Completed(long paperId,String filename,long byteSize,String sha256,String expectedHash) {}
    private static final class Upload {
        final long owner,paperId,size; final String filename,expectedHash; final Path path;
        Instant touched; long received; int next; Completed completed;
        Upload(long owner,long paperId,String filename,long size,String hash,Path path,Instant now){
            this.owner=owner;this.paperId=paperId;this.filename=filename;this.size=size;this.expectedHash=hash;this.path=path;this.touched=now;
        }
    }
    public PaperFileStore(Path root,long maxBytes,Clock clock) throws IOException {
        if(maxBytes<1 || maxBytes>200L*1024*1024)throw new IllegalArgumentException("论文大小上限必须在 1 字节至 200 MiB 之间");
        this.root=root.toAbsolutePath().normalize();this.pending=this.root.resolve("pending");this.maxBytes=maxBytes;this.clock=clock;
        Files.createDirectories(pending);cleanup();
        cleaner=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"paper-upload-cleanup");t.setDaemon(true);return t;});
        cleaner.scheduleWithFixedDelay(()->{try{cleanup();}catch(IOException e){System.err.println("论文临时文件清理失败");}},60,60,TimeUnit.SECONDS);
    }
    public static PaperFileStore fromEnvironment() throws IOException {
        return new PaperFileStore(Path.of(System.getenv().getOrDefault("VCAMPUS_PAPER_DIR","data/library-papers")),
                Long.parseLong(System.getenv().getOrDefault("VCAMPUS_PAPER_MAX_BYTES","31457280")),Clock.systemUTC());
    }
    public synchronized String start(long owner,long paperId,String filename,long size,String expectedHash) throws IOException {
        cleanup();
        if(size<10 || size>maxBytes)throw new IllegalArgumentException("PDF 大小须为 10 字节至 "+maxBytes/1024/1024+" MiB");
        if(filename==null || filename.length()>180 || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf") || filename.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*"))
            throw new IllegalArgumentException("请选择文件名合法的 PDF 文件");
        if(uploads.size()>=8 || uploads.values().stream().filter(u->u.owner==owner).count()>=2)
            throw new IllegalArgumentException("上传任务已满，请完成或取消现有上传后重试");
        String id=UUID.randomUUID().toString();Path path=pending.resolve(id+".part");Files.createFile(path);
        uploads.put(id,new Upload(owner,paperId,filename,size,expectedHash,path,clock.instant()));return id;
    }
    private Upload owned(long owner,String id) {
        Upload u=uploads.get(id);
        if(u==null || u.owner!=owner || !u.touched.plus(TTL).isAfter(clock.instant()))throw new IllegalArgumentException("上传已过期或无权访问，请重新上传");
        u.touched=clock.instant();return u;
    }
    public synchronized void append(long owner,String id,int index,byte[] bytes) throws IOException {
        Upload u=owned(owner,id);
        if(u.completed!=null || index!=u.next || bytes.length<1 || bytes.length>CHUNK_BYTES || u.received+bytes.length>u.size)
            throw new IllegalArgumentException("PDF 分块顺序或大小无效");
        Files.write(u.path,bytes,StandardOpenOption.APPEND);u.received+=bytes.length;u.next++;
    }
    public synchronized Completed complete(long owner,String id) throws IOException {
        Upload u=owned(owner,id);if(u.completed!=null)return u.completed;
        if(u.received!=u.size)throw new IllegalArgumentException("PDF 上传不完整，请重新上传");
        // Lightweight format guard, not malware scanning or full PDF rendering validation.
        try(var file=new RandomAccessFile(u.path.toFile(),"r")) {
            byte[] start=new byte[8];file.readFully(start);
            if(!new String(start,StandardCharsets.US_ASCII).matches("%PDF-[12]\\.[0-9]"))throw new IllegalArgumentException("文件不是有效的 PDF");
            byte[] tail=new byte[(int)Math.min(2048,u.size)];file.seek(u.size-tail.length);file.readFully(tail);
            if(!new String(tail,StandardCharsets.ISO_8859_1).stripTrailing().endsWith("%%EOF"))throw new IllegalArgumentException("PDF 缺少完整结束标记");
        }
        MessageDigest digest=sha();try(var in=Files.newInputStream(u.path)){byte[] buffer=new byte[CHUNK_BYTES];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        String hash=HexFormat.of().formatHex(digest.digest());Path target=path(hash);
        try{Files.move(u.path,target);}catch(FileAlreadyExistsException e){Files.deleteIfExists(u.path);}
        u.completed=new Completed(u.paperId,u.filename,u.size,hash,u.expectedHash);return u.completed;
    }
    public synchronized void consume(long owner,String id) throws IOException {owned(owner,id);cancel(owner,id);}
    public synchronized void cancel(long owner,String id) throws IOException {
        Upload u=uploads.get(id);if(u==null)return;
        if(u.owner!=owner)throw new IllegalArgumentException("无权取消该上传");Files.deleteIfExists(u.path);uploads.remove(id);
    }
    public byte[] read(String hash,long offset,int length) throws IOException {
        if(offset<0 || length<1 || length>CHUNK_BYTES)throw new IllegalArgumentException("PDF 分块无效");
        try(var file=new RandomAccessFile(path(hash).toFile(),"r")){
            if(offset>=file.length())throw new IllegalArgumentException("PDF 分块超出文件范围");
            byte[] b=new byte[(int)Math.min(length,file.length()-offset)];file.seek(offset);file.readFully(b);return b;
        }
    }
    private Path path(String hash){if(hash==null || !hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("PDF 标识无效");return root.resolve(hash+".pdf");}
    public synchronized void cleanup() throws IOException {
        Instant cutoff=clock.instant().minus(TTL);
        var iterator=uploads.entrySet().iterator();while(iterator.hasNext()){var u=iterator.next().getValue();if(!u.touched.isAfter(cutoff)){Files.deleteIfExists(u.path);iterator.remove();}}
        Set<Path> active=new HashSet<>();uploads.values().forEach(u->active.add(u.path));
        try(var files=Files.newDirectoryStream(pending,"*.part")){for(Path p:files)if(!active.contains(p)&&Files.getLastModifiedTime(p).toInstant().isBefore(cutoff))Files.deleteIfExists(p);}
    }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Override public synchronized void close(){cleaner.shutdownNow();for(var u:uploads.values())try{Files.deleteIfExists(u.path);}catch(IOException ignored){}uploads.clear();}
}
