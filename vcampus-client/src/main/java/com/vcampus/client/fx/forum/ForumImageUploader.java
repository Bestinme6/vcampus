package com.vcampus.client.fx.forum;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.DoubleConsumer;

public final class ForumImageUploader {
    private static final long MAX_BYTES=5L*1024*1024;
    private final ForumGateway gateway;
    public ForumImageUploader(ForumGateway gateway){this.gateway=gateway;}
    public List<String> upload(List<ForumImageDraft> drafts,DoubleConsumer progress)throws IOException{
        if(drafts.size()>3)throw new IOException("每篇帖子最多三张图片");List<String> ids=new ArrayList<>();long total=drafts.stream().mapToLong(ForumImageDraft::byteSize).sum(),sent=0;
        for(ForumImageDraft draft:drafts){validate(draft);var ticket=gateway.imageUploadStart(draft.mimeType(),draft.byteSize());if(ticket.expectedBytes()!=draft.byteSize()||ticket.chunkBytes()<1)throw new IOException("上传凭据与文件不匹配");
            try(InputStream input=Files.newInputStream(draft.path())){for(int index=0,read;;index++){byte[] chunk=input.readNBytes(ticket.chunkBytes());if(chunk.length==0)break;gateway.imageUploadChunk(ticket.uploadId(),index,chunk);sent+=chunk.length;progress.accept(total==0?1:(double)sent/total);}}
            gateway.imageUploadComplete(ticket.uploadId());ids.add(ticket.uploadId());
        }return List.copyOf(ids);
    }
    private void validate(ForumImageDraft draft)throws IOException{if(draft.byteSize()<1||draft.byteSize()>MAX_BYTES||!Files.isRegularFile(draft.path())||Files.size(draft.path())!=draft.byteSize())throw new IOException("每张图片不能超过5 MiB");if(!Set.of("image/jpeg","image/png","image/webp").contains(draft.mimeType()))throw new IOException("仅支持 JPG、PNG 或 WebP 图片");}
}
