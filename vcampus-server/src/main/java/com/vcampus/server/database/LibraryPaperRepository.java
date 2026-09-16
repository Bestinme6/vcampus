package com.vcampus.server.database;

import com.vcampus.common.model.LibraryPaper;
import java.sql.*;
import java.util.*;

public final class LibraryPaperRepository implements LibraryPaperStore {
    private final ConnectionFactory connections;
    public LibraryPaperRepository(ConnectionFactory connections){this.connections=connections;}
    @Override public Page search(String keyword,String year,String tag,int page,int size,boolean includeDisabled,long viewerId) throws SQLException {
        String where=" WHERE (? OR enabled=1 OR (created_by=? AND sha256 IS NULL)) AND (?='' OR publish_year=?) AND (?='' OR LOCATE(?,title)>0 OR LOCATE(?,authors)>0 OR LOCATE(?,keywords)>0)"
                +" AND (?='' OR FIND_IN_SET(?,REPLACE(REPLACE(REPLACE(keywords,'；',','),';',','),'，',','))>0)";
        try(var c=connections.openConnection()) {
            int total;try(var s=c.prepareStatement("SELECT COUNT(*) FROM library_papers"+where)){filters(s,keyword,year,tag,includeDisabled,viewerId);try(var r=s.executeQuery()){r.next();total=r.getInt(1);}}
            List<LibraryPaper> rows=new ArrayList<>();
            try(var s=c.prepareStatement("SELECT * FROM library_papers"+where+" ORDER BY id DESC LIMIT ? OFFSET ?")){
                filters(s,keyword,year,tag,includeDisabled,viewerId);s.setInt(11,size);s.setLong(12,(long)(page-1)*size);
                try(var r=s.executeQuery()){while(r.next())rows.add(row(r));}
            }
            var keywords=new TreeSet<String>();
            try(var s=c.prepareStatement("SELECT keywords FROM library_papers WHERE (? OR enabled=1 OR (created_by=? AND sha256 IS NULL)) AND keywords<>'' ORDER BY id DESC LIMIT 2000")){
                s.setBoolean(1,includeDisabled);s.setLong(2,viewerId);try(var r=s.executeQuery()){while(r.next())keywords.addAll(com.vcampus.common.model.PaperKeywords.parse(r.getString(1)));}
            }
            return new Page(rows,page,total,keywords.stream().limit(50).toList());
        }
    }
    private void filters(PreparedStatement s,String key,String year,String tag,boolean hidden,long viewerId)throws SQLException{
        s.setBoolean(1,hidden);s.setLong(2,viewerId);s.setString(3,year);s.setString(4,year);for(int i=5;i<=8;i++)s.setString(i,key);s.setString(9,tag);s.setString(10,tag);
    }
    @Override public Optional<LibraryPaper> find(long id)throws SQLException {
        try(var c=connections.openConnection();var s=c.prepareStatement("SELECT * FROM library_papers WHERE id=?")){
            s.setLong(1,id);try(var r=s.executeQuery()){return r.next()?Optional.of(row(r)):Optional.empty();}
        }
    }
    @Override public long save(long id,Metadata m,long actor,boolean manager)throws SQLException {
        return transaction(c->{
            String sql=id==0?"INSERT INTO library_papers (title,authors,abstract_text,keywords,publish_year,venue,doi,source_url,rights_note,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)":
                    "UPDATE library_papers SET title=?,authors=?,abstract_text=?,keywords=?,publish_year=?,venue=?,doi=?,source_url=?,rights_note=? WHERE id=?";
            if(id!=0){var old=locked(c,id);authorizeMutation(old,actor,manager);if(old.hasPdf()&&m.rights().isBlank())throw new IllegalArgumentException("带 PDF 的论文必须保留来源与分享说明");}
            long result=id;try(var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){
                s.setString(1,m.title());s.setString(2,m.authors());s.setString(3,m.abstractText());s.setString(4,m.keywords());s.setString(5,m.year());s.setString(6,m.venue());
                s.setString(7,m.doi().isBlank()?null:m.doi());s.setString(8,m.sourceUrl());s.setString(9,m.rights());s.setLong(10,id==0?actor:id);s.executeUpdate();
                if(id==0)try(var keys=s.getGeneratedKeys()){if(!keys.next())throw new SQLException("Missing generated paper ID");result=keys.getLong(1);}
            }audit(c,actor,"library.paper.save");return result;
        });
    }
    @Override public void setEnabled(long id,boolean enabled,long actor)throws SQLException {
        transaction(c->{var paper=locked(c,id);if(enabled&&!paper.hasPdf())throw new IllegalArgumentException("请先上传 PDF，再公开资源");try(var s=c.prepareStatement("UPDATE library_papers SET enabled=? WHERE id=?")){s.setBoolean(1,enabled);s.setLong(2,id);s.executeUpdate();}audit(c,actor,"library.paper.setEnabled");return null;});
    }
    @Override public void attach(long id,String filename,long size,String hash,String expectedHash,long actor,boolean manager,boolean publishFirstPdf)throws SQLException {
        transaction(c->{var old=locked(c,id);
            authorizeMutation(old,actor,manager);
            if(old.rights().isBlank())throw new IllegalArgumentException("请先填写来源与分享说明");
            if(!old.sha256().equals(expectedHash) && !old.sha256().equals(hash))throw new IllegalArgumentException("PDF 已被其他用户替换，请刷新后重试");
            boolean enabled=old.enabled() || publishFirstPdf&&!old.hasPdf();
            try(var s=c.prepareStatement("UPDATE library_papers SET filename=?,byte_size=?,sha256=?,enabled=? WHERE id=?")){
                s.setString(1,filename);s.setLong(2,size);s.setString(3,hash);s.setBoolean(4,enabled);s.setLong(5,id);s.executeUpdate();
            }audit(c,actor,"library.paper.upload.complete");return null;
        });
    }
    private static void authorizeMutation(LibraryPaper paper,long actor,boolean manager){
        if(!manager&&paper.createdBy()!=actor)throw new IllegalArgumentException("只能维护自己上传的资源");
        if(!manager&&!paper.enabled()&&paper.hasPdf())throw new IllegalArgumentException("该资源已由管理员下架，不能继续修改或替换 PDF");
    }
    private LibraryPaper locked(Connection c,long id)throws SQLException {
        try(var s=c.prepareStatement("SELECT * FROM library_papers WHERE id=? FOR UPDATE")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("论文不存在");return row(r);}}
    }
    private LibraryPaper row(ResultSet r)throws SQLException {
        return new LibraryPaper(r.getLong("id"),r.getLong("created_by"),r.getString("title"),r.getString("authors"),r.getString("abstract_text"),r.getString("keywords"),r.getString("publish_year"),r.getString("venue"),
                Objects.requireNonNullElse(r.getString("doi"),""),r.getString("source_url"),r.getString("rights_note"),r.getBoolean("enabled"),r.getString("filename"),r.getLong("byte_size"),Objects.requireNonNullElse(r.getString("sha256"),""));
    }
    private void audit(Connection c,long actor,String action)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO audit_logs (user_id,action_code,result_code,client_address) VALUES (?,?,'SUCCESS','')")){s.setLong(1,actor);s.setString(2,action);s.executeUpdate();}
    }
    private <T>T transaction(Work<T> work)throws SQLException {
        try(var c=connections.openConnection()){c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
}
