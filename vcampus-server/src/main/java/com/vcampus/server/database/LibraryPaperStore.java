package com.vcampus.server.database;

import com.vcampus.common.model.LibraryPaper;
import java.sql.SQLException;
import java.util.*;

public interface LibraryPaperStore {
    record Metadata(String title,String authors,String abstractText,String keywords,String year,String venue,String doi,String sourceUrl,String rights) {}
    record Page(List<LibraryPaper> rows,int page,int total) {}
    Page search(String keyword,String year,int page,int size,boolean includeDisabled) throws SQLException;
    Optional<LibraryPaper> find(long id) throws SQLException;
    long save(long id,Metadata metadata,long actor) throws SQLException;
    void setEnabled(long id,boolean enabled,long actor) throws SQLException;
    void attach(long id,String filename,long size,String hash,String expectedHash,long actor) throws SQLException;
}
