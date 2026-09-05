package com.vcampus.client.fx.library;

import com.vcampus.client.ui.LibraryViewData;
import com.vcampus.common.model.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryViewTest {
    @BeforeAll static void toolkit()throws Exception{LibraryAdminViewTest.toolkit();}
    @Test void damagedOnlyCopiesDoNotEnableReservationButOnLoanDoes()throws Exception{
        LibraryAdminViewTest.fx(()->{
            var view=new LibraryView(Set.of(UserRole.STUDENT),new Listener());scene(view,1220,1024);view.showCatalog(example());view.applyCss();view.layout();
            assertFalse(((Button)view.lookup("#library-reserve-2")).isDisabled());
            assertTrue(((Button)view.lookup("#library-borrow-2")).isDisabled());
            assertTrue(((Button)view.lookup("#library-reserve-3")).isDisabled());
            assertTrue(((Button)view.lookup("#library-borrow-3")).isDisabled());return null;
        });
    }
    @Test void searchSortAndBookActionsUseSelectedValues()throws Exception{
        LibraryAdminViewTest.fx(()->{
            Listener events=new Listener();var view=new LibraryView(Set.of(UserRole.STUDENT),events);scene(view,1220,1024);view.showCatalog(example());view.applyCss();view.layout();
            ((TextField)view.lookup("#library-category")).setText("文学");
            @SuppressWarnings("unchecked") ComboBox<LibrarySort> sort=(ComboBox<LibrarySort>)view.lookup("#library-sort");sort.setValue(LibrarySort.BORROW_COUNT_DESC);
            assertEquals("文学",events.category);assertEquals(LibrarySort.BORROW_COUNT_DESC,events.sort);assertEquals(1,events.page);
            ((Button)view.lookup("#library-reserve-2")).fire();assertEquals(2,events.reserved);
            ((Button)view.lookup("#library-detail-1")).fire();assertEquals(1,events.detail);return null;
        });
    }
    @Test void teacherHasLoansButNoReservationControls()throws Exception{
        LibraryAdminViewTest.fx(()->{
            var view=new LibraryView(Set.of(UserRole.TEACHER),new Listener());scene(view,1220,1024);view.showCatalog(example());view.applyCss();view.layout();
            assertNull(view.lookup("#library-tab-reservations"));assertNull(view.lookup("#library-reserve-2"));assertNotNull(view.lookup("#library-tab-loans"));return null;
        });
    }
    @Test void renderCatalogDetailAndReservationsAtDesktopSizes()throws Exception{
        LibraryAdminViewTest.fx(()->{
            var view=new LibraryView(Set.of(UserRole.STUDENT),new Listener());scene(view,1220,1024);view.showCatalog(example());
            capture(view,"library-catalog-1220",1220,1024);capture(view,"library-catalog-780",780,720);
            view.showDetail(example().rows().getFirst());capture(view,"library-detail-780",780,720);
            view.showReservations(new LibraryData.Page<>(List.of(
                    new LibraryData.Reservation(1,2,"BK000000002","计算机网络导论","WAITING",Instant.parse("2026-08-31T02:00:00Z"),null),
                    new LibraryData.Reservation(2,4,"BK000000004","世界文明史","NOTIFIED",Instant.parse("2026-08-29T02:00:00Z"),Instant.parse("2026-08-31T01:00:00Z"))),1,10,2));
            capture(view,"library-reservations-1220",1220,1024);return null;
        });
    }
    static void scene(Parent view,int width,int height){if(view.getScene()!=null)view.getScene().setRoot(new Group());new Scene(view,width,height);view.resize(width,height);view.applyCss();view.layout();}
    static void capture(Parent view,String name,int width,int height)throws Exception{
        if(view.getScene()==null)scene(view,width,height);else{view.resize(width,height);view.applyCss();view.layout();}
        Path folder=Path.of("target","library-qa");Files.createDirectories(folder);
        ImageIO.write(SwingFXUtils.fromFXImage(view.snapshot(null,null),null),"png",folder.resolve(name+".png").toFile());
    }
    static LibraryData.Page<LibraryData.Book> example(){
        String[] titles={"程序设计实践","计算机网络导论","文学与生活","世界文明史","设计基础","科学的探索"};
        String[] categories={"计算机","计算机","文学","历史","艺术","科学"};
        var books=new ArrayList<LibraryData.Book>();
        for(int i=0;i<titles.length;i++)books.add(new LibraryData.Book(new LibraryViewData.CatalogRow(i+1,"BK00000000"+(i+1),"",titles[i],"示例作者","校园示例出版社",2026,categories[i],
                "这是用于界面验证的示例书目。正式运行时，内容介绍与馆藏信息均从校园服务器读取。",true,4,(i==1||i==2)?0:2),i==2?0:2,36+i*17));
        return new LibraryData.Page<>(books,1,10,6);
    }
    static class Listener implements LibraryView.Listener{
        String category;LibrarySort sort;int page;long reserved,detail;
        public void open(String r){}public void back(){}public void search(String k,String c,LibrarySort s,int p){category=c;sort=s;page=p;}
        public void detail(long id){detail=id;}public void borrow(LibraryData.Book b){}public void reserve(LibraryData.Book b){reserved=b.book().bookId();}
        public void loans(String s,int p){}public void reservations(String s,int p){}public void renew(LibraryViewData.LoanRow x){}public void returnLoan(LibraryViewData.LoanRow x){}public void cancel(LibraryData.Reservation x){}
    }
}
