package com.kareem.cortex;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CognitiveAuthorityRouterTest {
    @Test public void bucketIsStableAndBounded(){
        int a=CognitiveAuthorityRouter.stableBucket("thread:42");
        assertEquals(a,CognitiveAuthorityRouter.stableBucket("thread:42"));
        assertTrue(a>=0&&a<100);
        for(int i=0;i<500;i++){int b=CognitiveAuthorityRouter.stableBucket("thread:"+i);assertTrue(b>=0&&b<100);}
    }

    @Test public void hardGateAlwaysWinsAndKillSwitchRestoresLegacy(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean oldEnabled=CognitiveFeatureFlags.authorityCanaryEnabled(c);int oldPercent=CognitiveFeatureFlags.canaryPercent(c);
        try{
            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,true);CognitiveFeatureFlags.setCanaryPercent(c,100);
            assertEquals(CognitiveAuthorityRouter.Route.HARD_GATE,CognitiveAuthorityRouter.route(c,11,"com.android.systemui","Battery",true));
            assertEquals(CognitiveAuthorityRouter.Route.V2_CANARY,CognitiveAuthorityRouter.route(c,11,"com.whatsapp","Ahmed",false));
            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,false);
            assertEquals(CognitiveAuthorityRouter.Route.LEGACY,CognitiveAuthorityRouter.route(c,11,"com.whatsapp","Ahmed",false));
        }finally{CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,oldEnabled);CognitiveFeatureFlags.setCanaryPercent(c,oldPercent);}
    }

    @Test public void percentZeroAndHundredAreExactAndThreadAssignmentIsStable(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean oldEnabled=CognitiveFeatureFlags.authorityCanaryEnabled(c);int oldPercent=CognitiveFeatureFlags.canaryPercent(c);
        try{
            CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,true);CognitiveFeatureFlags.setCanaryPercent(c,0);
            assertEquals(CognitiveAuthorityRouter.Route.LEGACY,CognitiveAuthorityRouter.route(c,77,"com.whatsapp","Ahmed",false));
            CognitiveFeatureFlags.setCanaryPercent(c,100);
            for(int i=0;i<10;i++)assertEquals(CognitiveAuthorityRouter.Route.V2_CANARY,CognitiveAuthorityRouter.route(c,77,"different.source."+i,"different sender "+i,false));
        }finally{CognitiveFeatureFlags.setAuthorityCanaryEnabled(c,oldEnabled);CognitiveFeatureFlags.setCanaryPercent(c,oldPercent);}
    }
}
