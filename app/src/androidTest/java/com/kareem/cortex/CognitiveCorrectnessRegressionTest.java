package com.kareem.cortex;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression cases taken from real Cortex product output, not synthetic UI-only fixtures. */
@RunWith(AndroidJUnit4.class)
public class CognitiveCorrectnessRegressionTest {

    @Test public void bundledYoutubeSummaryCannotRenderAsDecision(){
        long now=System.currentTimeMillis();
        PrimeBriefStore.Item x=item(1,"DECISION","YouTube · Decision","[1] 3 new messages [2] Google: You created a new playlist","youtube",now);
        assertTrue(CandidateConsolidator.legacyNotificationBundle(x.title+" "+x.body,x.source,x.kind));
        assertEquals("CONTEXT",CandidateConsolidator.effectiveKind(x));
    }

    @Test public void automatedAccountInfoCannotRenderAsUserActionWithoutObligation(){
        long now=System.currentTimeMillis();
        PrimeBriefStore.Item x=item(1,"ACTION","Notion Team · Action","Google Important info about Nassour's Google Account","gmail",now);
        assertTrue(CandidateConsolidator.automatedInformationalAction(x.title+" "+x.body,x.source,x.kind));
        assertEquals("CONTEXT",CandidateConsolidator.effectiveKind(x));
    }

    @Test public void explicitAccountRequestCanRemainAction(){
        long now=System.currentTimeMillis();
        PrimeBriefStore.Item x=item(1,"ACTION","Google · Action","Action required: please confirm access to your Google Account","gmail",now);
        assertFalse(CandidateConsolidator.automatedInformationalAction(x.title+" "+x.body,x.source,x.kind));
        assertEquals("ACTION",CandidateConsolidator.effectiveKind(x));
    }

    @Test public void repeatedCibDeclineCollapsesWithinSameEventWindow(){
        long bucket=2L*60L*60L*1000L;
        long now=(System.currentTimeMillis()/bucket)*bucket+15L*60L*1000L;
        PrimeBriefStore.Item a=item(1,"DECISION","CIB · Decision","[1] لقد تم رفض المعاملة من Google Spotify على بطاقتكم","CIB",now);
        PrimeBriefStore.Item b=item(2,"DECISION","CIB · Decision","لقد تم رفض المعاملة من Google Spotify على بطاقتكم","cib",now+12L*60L*1000L);
        assertEquals("ALERT",CandidateConsolidator.effectiveKind(a));
        assertEquals("ALERT",CandidateConsolidator.effectiveKind(b));
        assertEquals("CIB · Alert",CandidateConsolidator.presentationTitle(a));
        assertTrue(CandidateConsolidator.sameEvent(a,b));
    }

    @Test public void laterCibDeclineWithoutAmountIsNotCollapsedForever(){
        long bucket=2L*60L*60L*1000L;
        long now=(System.currentTimeMillis()/bucket)*bucket+15L*60L*1000L;
        PrimeBriefStore.Item a=item(1,"DECISION","CIB · Decision","لقد تم رفض المعاملة من Google Spotify على بطاقتكم","CIB",now);
        PrimeBriefStore.Item b=item(2,"DECISION","CIB · Decision","لقد تم رفض المعاملة من Google Spotify على بطاقتكم","CIB",now+5L*60L*60L*1000L);
        assertFalse(CandidateConsolidator.sameEvent(a,b));
    }

    @Test public void contactLabelsDoNotBecomePersonNames(){
        assertEquals("M Zeen",EntityDisplayNamePolicy.cleanContactName("M Zeen Phone: 01229577182"));
        assertEquals("Osama Sa2f M3ala2",EntityDisplayNamePolicy.cleanContactName("Osama Sa2f M3ala2 Phone: 01001512044"));
        assertEquals("Bro",EntityDisplayNamePolicy.cleanContactName("Bro New Number"));
        assertEquals("Eng Ahmed Shoeib",EntityDisplayNamePolicy.cleanContactName("Eng Ahmed Shoeib"));
    }

    private static PrimeBriefStore.Item item(long id,String kind,String title,String body,String source,long updated){
        return new PrimeBriefStore.Item(id,kind,title,body,source,"open",.90,70,0,0,updated);
    }
}