package com.vcampus.client.fx.forum;

import com.vcampus.client.ui.ForumViewData.*;
import com.vcampus.common.model.*;
import java.time.Instant;
import java.util.*;

final class ForumFixtures {
    static List<SectionRow> sections(){return List.of(new SectionRow(1,"CAMPUS","校园生活","校园里的新鲜事",10,true),new SectionRow(2,"STUDY","学习问答","一起讨论，共同成长",20,true),new SectionRow(3,"ACTIVITY","活动交流","发现同好",30,true),new SectionRow(4,"MARKET","闲置交换","物尽其用",40,true));}
    static ForumData.FeedPage feed(){
        String[] titles={"开学一周，大家觉得哪家食堂窗口最值得回购？","数据结构课程设计怎么选题？求一些工作量合适的方向","本周六校园定向越野招募队友，还差两位同学","出一台九成新 24 寸显示器，可校内自提"};
        String[] summaries={"最近在桃园和梅园之间反复横跳，想整理一份不踩雷清单。欢迎分享菜品、价格和排队时间。","两人组，希望题目能覆盖图、查找和文件存储，同时方便做可视化演示。","周六下午在体育馆集合，不要求运动基础，主要是一起完成打卡任务。","毕业整理闲置，显示正常，配件齐全。感兴趣的同学可以评论交流。"};
        List<ForumData.FeedRow> rows=new ArrayList<>();
        for(int i=0;i<4;i++) rows.add(new ForumData.FeedRow(new PostRow(i+1,i+1,sections().get(i).name(),10+i,List.of("林清禾","周明远","陈星语","李明").get(i),titles[i],summaries[i],ForumContentStatus.NORMAL,false,i==0,i==0,120-i*10,18-i*4,Instant.parse("2026-08-31T08:00:00Z").minusSeconds(i*1800L),null),new ForumData.Engagement(42-i*10,false,false),false));
        return new ForumData.FeedPage(rows,1,20,4);
    }
    static PostDetail post(boolean locked){return new PostDetail(1,1,"校园生活",10,"林清禾",feed().rows().getFirst().post().title(),"开学第一周，终于把几家食堂都逛了一遍。\n\n想邀请大家一起整理一份校园食堂回购清单：\n\n1. 推荐哪一道菜？\n2. 人均大概多少钱？\n3. 哪个时间段不用排太久？\n\n我先来：昨天吃到的番茄牛腩很不错，分量也足。欢迎补充你们的私藏窗口！",ForumContentStatus.NORMAL,locked,true,true,128,2,Instant.parse("2026-08-31T08:00:00Z"),Instant.parse("2026-08-31T08:00:00Z"),null,false);}
    static ForumData.CommentPage comments(){return new ForumData.CommentPage(List.of(new ForumData.Comment(new CommentRow(1,1,12,"周明远","推荐二楼的面馆，午高峰过后基本不用排队。",ForumContentStatus.NORMAL,Instant.parse("2026-08-31T09:00:00Z"),false),null,"",false),new ForumData.Comment(new CommentRow(2,1,13,"陈星语","谢谢分享，明天就去试试！",ForumContentStatus.NORMAL,Instant.parse("2026-08-31T09:30:00Z"),true),3L,"",false)),1,10,2);}
}
