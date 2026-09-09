package com.vcampus.client.fx.library;

import com.vcampus.common.model.LibraryPaper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PaperLinks {
    private PaperLinks(){}
    public static Map<String,String> searches(String keyword){
        String q=URLEncoder.encode(keyword.strip(),StandardCharsets.UTF_8);Map<String,String> links=new LinkedHashMap<>();
        links.put("Google Scholar", "https://scholar.google.com/scholar?q="+q);
        links.put("arXiv", "https://arxiv.org/search/?query="+q+"&searchtype=all");return Collections.unmodifiableMap(links);
    }
    public static Optional<String> original(LibraryPaper paper){
        if(!paper.sourceUrl().isBlank())return safeUrl(paper.sourceUrl());
        if(!paper.doi().isBlank())return Optional.of("https://doi.org/"+URLEncoder.encode(paper.doi(),StandardCharsets.UTF_8).replace("+","%20").replace("%2F","/"));
        return Optional.empty();
    }
    static Optional<String> safeUrl(String value){
        try{URI u=URI.create(value);if(Set.of("http","https").contains(Objects.requireNonNullElse(u.getScheme(),"").toLowerCase(Locale.ROOT))&&u.getHost()!=null&&u.getUserInfo()==null)return Optional.of(u.toASCIIString());}
        catch(IllegalArgumentException ignored){}return Optional.empty();
    }
    public static String safeFilename(LibraryPaper paper){
        String name=paper.title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").replaceAll("[. ]+$","");
        if(name.isBlank())name="paper";if(name.length()>100)name=name.substring(0,100);
        return "论文-"+paper.id()+"-"+name+".pdf";
    }
}
