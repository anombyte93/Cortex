package com.kareem.cortex;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InboxPresentationRegressionTest {

    @Test public void openActionBecomesSemanticTitleAndDueIsSeparated(){
        KnowledgeItem k=item("Voice: Voice note","راجع رسومات المشروع بكرة الصبح","done");
        ArrayList<String> actions=new ArrayList<>();
        actions.add("ابعت النسخة النهائية للعميل  •  due: 2026-08-29 10:00");
        assertEquals("ابعت النسخة النهائية للعميل",InboxPresentation.title(k,actions));
        assertFalse(InboxPresentation.title(k,actions).toLowerCase().contains("due:"));
        assertFalse(InboxPresentation.due(actions).isEmpty());
    }

    @Test public void genericTransportTitleFallsBackToMeaning(){
        KnowledgeItem k=item("Memory","محتاج أراجع عرض السعر مع أحمد قبل الاجتماع.","done");
        assertEquals("محتاج أراجع عرض السعر مع أحمد قبل الاجتماع.",InboxPresentation.title(k,new ArrayList<>()));
    }

    @Test public void specificHumanTitleIsPreserved(){
        KnowledgeItem k=item("Villa Haram bathroom quantities","تفاصيل تشطيب الحمام","done");
        assertEquals("Villa Haram bathroom quantities",InboxPresentation.title(k,new ArrayList<>()));
    }

    @Test public void attentionScoreIsPresentedAsSignalNotProbability(){
        KnowledgeItem k=item("Memory","Follow up with supplier","done");
        String label=InboxPresentation.signalLabel(k,78,new ArrayList<>());
        assertEquals("strong signal",label);
        assertFalse(label.toLowerCase().contains("confidence"));
    }

    @Test public void failedAnalysisShowsIncompleteState(){
        KnowledgeItem k=item("Voice note","","analysis_failed");
        assertEquals("Analysis incomplete",InboxPresentation.stateLabel(k,"Needs attention",false,false,new ArrayList<>()));
        assertEquals("needs retry",InboxPresentation.signalLabel(k,120,new ArrayList<>()));
    }

    private static KnowledgeItem item(String title,String summary,String status){
        long now=System.currentTimeMillis();
        return new KnowledgeItem(1,"AUDIO","manual_recording",title,"",summary,summary,"","","",status,"","","{}",now,now);
    }
}
