package com.kareem.cortex;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression coverage for real Now-surface failures observed on-device. */
@RunWith(AndroidJUnit4.class)
public class TodaySurfaceRegressionTest {

    @Test public void automatedGoogleAccountBulletinCannotConsumeNeedsYouSlot(){
        PrimeBriefStore.Item x=item(1,"ACTION","Notion Team · Action","Google Important info about Nassour's Google Account","Notion Team",System.currentTimeMillis());
        assertTrue(PrimeBriefStore.hardSurfaceNoise(x));
    }

    @Test public void explicitAccountObligationIsNotSuppressed(){
        PrimeBriefStore.Item x=item(1,"ACTION","Google · Action","Action required: please confirm access to your Google Account","Google",System.currentTimeMillis());
        assertFalse(PrimeBriefStore.hardSurfaceNoise(x));
    }

    @Test public void identicalCibAlertsCollapseOnTodayEvenAcrossEventBuckets(){
        long now=System.currentTimeMillis();
        PrimeBriefStore.Item a=item(1,"DECISION","CIB · Decision","[1] لقد تم رفض المعاملة من Google Spotify على بطاقتكم","CIB",now);
        PrimeBriefStore.Item b=item(2,"DECISION","CIB · Decision","لقد تم رفض المعاملة من Google Spotify على بطاقتكم","CIB",now-5L*60L*60L*1000L);
        assertEquals("ALERT",a.attentionKind);
        assertEquals("ALERT",b.attentionKind);
        assertTrue(PrimeBriefStore.sameSurfaceEvent(a,b));
    }

    private static PrimeBriefStore.Item item(long id,String kind,String title,String body,String source,long updated){
        return new PrimeBriefStore.Item(id,kind,title,body,source,"open",.90,70,0,0,updated);
    }
}
