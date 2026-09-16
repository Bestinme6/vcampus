package com.vcampus.server.database;

import com.vcampus.common.model.LibraryPaper;
import java.sql.SQLException;
import java.util.*;

public interface LibraryPaperStore {
    record Metadata(String title,String authors,String abstractText,String keywords,String year,String venue,String doi,String sourceUrl,String rights) {}
    record Page(List<LibraryPaper> rows,int page,int total,List<String> keywords) {
        public Page { rows=List.copyOf(rows);keywords=List.copyOf(keywords); }
    }
    Page search(String keyword,String year,String tag,int page,int size,boolean includeDisabled,long viewerId) throws SQLException;
    Optional<LibraryPaper> find(long id) throws SQLException;
    long save(long id,Metadata metadata,long actor,boolean manager) throws SQLException;
    void setEnabled(long id,boolean enabled,long actor) throws SQLException;
    void attach(long id,String filename,long size,String hash,String expectedHash,long actor,boolean manager,boolean publishFirstPdf) throws SQLException;
}
