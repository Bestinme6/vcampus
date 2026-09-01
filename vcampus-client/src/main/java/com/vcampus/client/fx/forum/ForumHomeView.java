package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.SectionRow;
import com.vcampus.common.model.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import java.util.*;
import java.util.function.*;

public final class ForumHomeView extends ScrollPane {
    private final VBox posts=new VBox(16),sidebar=new VBox(16),hot=new VBox(14),notices=new VBox(14);
    private final FlowPane sorts=new FlowPane(10,8),categories=new FlowPane(8,8);
    private final HBox paging=new HBox(12);
    private final Label count=ForumStyles.label("","forum-caption");
    private final Consumer<ForumData.FeedFilter> change;private final LongConsumer open;
    private ForumData.FeedFilter filter=ForumData.FeedFilter.home();
    public ForumHomeView(Consumer<ForumData.FeedFilter> change,LongConsumer open) {
        this.change=change;this.open=open;sidebar.setId("forum-sidebar");
        var bar=ForumStyles.row(sorts,ForumStyles.spacer(),count);bar.getStyleClass().add("forum-sort-bar");
        VBox main=new VBox(18,bar,categories,posts,paging);main.setMinWidth(0);HBox.setHgrow(main,Priority.ALWAYS);
        sidebar.setMinWidth(280);sidebar.setPrefWidth(280);sidebar.setMaxWidth(280);
        var noticeCard=ForumStyles.card(ForumStyles.label("校园公告","forum-section-title"),notices);
        var hotCard=ForumStyles.card(ForumStyles.label("今日热榜","forum-section-title"),hot);
        hotCard.getStyleClass().add("forum-hot-card");
        sidebar.getChildren().addAll(noticeCard,hotCard,ForumStyles.card(ForumStyles.label("让交流更有温度","forum-section-title"),
                ForumStyles.label("分享真实经历，尊重不同观点。请勿公开他人的联系方式与隐私。","forum-muted")));
        var columns=new HBox(24,main,sidebar);columns.getStyleClass().add("forum-content");
        ForumStyles.scroll(this,columns);
        widthProperty().addListener((o,a,b)->{boolean visible=b.doubleValue()>=1080;sidebar.setManaged(visible);sidebar.setVisible(visible);});
        sideLoading();
    }
    public void show(ForumData.FeedFilter filter,ForumData.FeedPage page,List<SectionRow> sections){
        this.filter=filter;count.setText("共 "+page.total()+" 条");
        sorts.getChildren().clear();
        for(var order:ForumFeedOrder.values()){
            String label=switch(order){case LATEST->"最新";case HOT->"热门";case FEATURED->"精华";};
            var b=ForumStyles.button(label,null,()->change.accept(new ForumData.FeedFilter(filter.scope(),order,filter.sectionId(),filter.keyword(),1)),"forum-sort");
            if(order==filter.order())b.getStyleClass().add("selected");sorts.getChildren().add(b);
        }
        categories.getChildren().clear();category("全部",null);
        sections.forEach(s->category(s.name(),s.id()));
        posts.getChildren().clear();
        if(page.rows().isEmpty())posts.getChildren().add(ForumStyles.card(ForumStyles.label("这里还很安静","forum-section-title"),
                ForumStyles.label(filter.scope()==ForumFeedScope.BOOKMARKS?"收藏感兴趣的帖子，下次更容易找到。":"试试其他分类或关键词，也欢迎发布第一篇分享。","forum-muted")));
        page.rows().forEach(row->posts.getChildren().add(new ForumPostCard(row,open)));
        paging.getChildren().clear();
        var previous=ForumStyles.button("上一页",Feather.CHEVRON_LEFT,()->page(filter.page()-1),"forum-quiet");
        var next=ForumStyles.button("下一页",Feather.CHEVRON_RIGHT,()->page(filter.page()+1),"forum-quiet");
        previous.setDisable(page.page()<=1);next.setDisable((long)page.page()*page.pageSize()>=page.total());
        paging.getChildren().addAll(previous,ForumStyles.label("第 "+page.page()+" 页","forum-caption"),next);
    }
    private void category(String name,Long id){
        var b=ForumStyles.button(name,null,()->change.accept(new ForumData.FeedFilter(filter.scope(),filter.order(),id,filter.keyword(),1)),"forum-category");
        if(Objects.equals(id,filter.sectionId()))b.getStyleClass().add("selected");categories.getChildren().add(b);
    }
    private void page(int page){change.accept(new ForumData.FeedFilter(filter.scope(),filter.order(),filter.sectionId(),filter.keyword(),page));}
    public void sideLoading(){hot.getChildren().setAll(ForumStyles.label("正在读取热榜…","forum-caption"));notices.getChildren().setAll(ForumStyles.label("正在读取公告…","forum-caption"));}
    public void hot(List<ForumData.FeedRow> rows){side(hot,rows,true,"今天还没有热门话题");}
    public void notices(List<ForumData.FeedRow> rows){side(notices,rows,false,"暂无管理员公告");}
    private void side(VBox target,List<ForumData.FeedRow> rows,boolean ranked,String empty){
        target.getChildren().clear();int index=1;
        for(var row:rows){
            var title=ForumStyles.button(row.post().title(),null,()->open.accept(row.post().id()),"forum-side-link");
            title.setWrapText(true);title.setMaxWidth(Double.MAX_VALUE);title.setMinWidth(0);
            if(ranked){var rank=ForumStyles.label(String.format("%02d",index++),"forum-rank");rank.setMinWidth(24);target.getChildren().add(ForumStyles.row(rank,title));}
            else target.getChildren().add(new VBox(6,title,ForumStyles.label("管理员公告 · "+ForumStyles.time(row.post().createdAt()),"forum-caption")));
        }
        if(rows.isEmpty())target.getChildren().add(ForumStyles.label(empty,"forum-caption"));
    }
    public void hotError(String message){hot.getChildren().setAll(ForumStyles.label(message,"forum-caption"));}
    public void noticeError(String message){notices.getChildren().setAll(ForumStyles.label(message+"；可使用上方公告入口重试。","forum-caption"));}
}
