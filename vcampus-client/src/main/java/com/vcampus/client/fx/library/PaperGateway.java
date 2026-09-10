package com.vcampus.client.fx.library;

import com.vcampus.common.model.LibraryPaper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.DoubleConsumer;

/** Blocking paper operations. Call only from a worker thread. */
public interface PaperGateway {
    record Page(List<LibraryPaper> rows,int page,int pageSize,int total,List<String> keywords) {
        public Page { rows=List.copyOf(rows);keywords=List.copyOf(keywords); }
    }
    Page search(String keyword,String year,String tag,int page,boolean includeDisabled) throws IOException;
    LibraryPaper get(long id) throws IOException;
    long save(long id,Map<String,String> metadata) throws IOException;
    void setEnabled(long id,boolean enabled) throws IOException;
    void upload(long id,Path source,DoubleConsumer progress) throws IOException;
    void download(LibraryPaper paper,Path destination,DoubleConsumer progress) throws IOException;
}
