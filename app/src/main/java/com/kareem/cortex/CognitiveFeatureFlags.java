package com.kareem.cortex;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Migration switches for Cognitive Brain V2. All authority switches are instant kill-switches. */
public final class CognitiveFeatureFlags {
    private static final String PREFS="cortex_cognitive_flags";
    private static final String AUTHORITY_MODE="cognitive_authority_mode";
    private static final String V2_SHADOW="cognitive_v2_shadow";
    private static final String V2_AUTHORITY_CANARY="cognitive_v2_authority_canary";
    private static final String V2_CANARY_PERCENT="cognitive_v2_canary_percent";
    private static final String V2_VALIDATION_OVERRIDE="cognitive_v2_validation_override";
    private static final String V2_VALIDATION_THREADS="cognitive_v2_validation_threads";

    /** Brain-first product line: V2 model authority owns meaningful signals unless explicitly killed/fallen back. */
    private static final CognitiveAuthorityMode DEFAULT_AUTHORITY_MODE=CognitiveAuthorityMode.V2_PRIMARY;
    private static final boolean DEFAULT_SHADOW=true;
    private static final boolean DEFAULT_AUTHORITY_CANARY=true;
    private static final int DEFAULT_CANARY_PERCENT=5;
    private static final boolean DEFAULT_VALIDATION_OVERRIDE=false;
    private static final String DEFAULT_VALIDATION_THREADS="";
    private static final int MAX_VALIDATION_THREADS=50;

    private CognitiveFeatureFlags(){}

    public static CognitiveAuthorityMode authorityMode(Context context){
        SharedPreferences p=prefs(context);
        if(p==null)return CognitiveAuthorityMode.LEGACY;
        String raw;
        try{raw=p.getString(AUTHORITY_MODE,DEFAULT_AUTHORITY_MODE.name());}
        catch(Throwable ignored){return DEFAULT_AUTHORITY_MODE;}
        if(raw==null)return DEFAULT_AUTHORITY_MODE;
        try{return CognitiveAuthorityMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));}
        catch(Throwable ignored){return DEFAULT_AUTHORITY_MODE;}
    }

    public static void setAuthorityMode(Context context,CognitiveAuthorityMode mode){
        SharedPreferences p=prefs(context);if(p!=null)p.edit().putString(AUTHORITY_MODE,(mode==null?DEFAULT_AUTHORITY_MODE:mode).name()).apply();
    }

    public static boolean shadowEnabled(Context context){
        SharedPreferences p=prefs(context);return p!=null&&p.getBoolean(V2_SHADOW,DEFAULT_SHADOW);
    }

    public static void setShadowEnabled(Context context,boolean enabled){
        SharedPreferences p=prefs(context);if(p!=null)p.edit().putBoolean(V2_SHADOW,enabled).apply();
    }

    /** Existing 7.2 safety switch remains the global V2-authority kill switch. */
    public static boolean authorityCanaryEnabled(Context context){
        SharedPreferences p=prefs(context);return p!=null&&p.getBoolean(V2_AUTHORITY_CANARY,DEFAULT_AUTHORITY_CANARY);
    }

    public static void setAuthorityCanaryEnabled(Context context,boolean enabled){
        SharedPreferences p=prefs(context);if(p!=null)p.edit().putBoolean(V2_AUTHORITY_CANARY,enabled).apply();
    }

    public static int canaryPercent(Context context){
        SharedPreferences p=prefs(context);int value=p==null?0:p.getInt(V2_CANARY_PERCENT,DEFAULT_CANARY_PERCENT);return clampPercent(value);
    }

    public static void setCanaryPercent(Context context,int percent){
        SharedPreferences p=prefs(context);if(p!=null)p.edit().putInt(V2_CANARY_PERCENT,clampPercent(percent)).apply();
    }

    public static boolean validationOverrideEnabled(Context context){
        return validationOverrideEnabled(context,BuildConfig.DEBUG);
    }

    static boolean validationOverrideEnabled(Context context,boolean debugBuild){
        SharedPreferences p=prefs(context);
        boolean prefEnabled=p!=null&&p.getBoolean(V2_VALIDATION_OVERRIDE,DEFAULT_VALIDATION_OVERRIDE);
        return debugBuild&&prefEnabled&&!validationThreadIds(context).isEmpty();
    }

    public static Set<Long> validationThreadIds(Context context){
        SharedPreferences p=prefs(context);
        String csv=p==null?DEFAULT_VALIDATION_THREADS:p.getString(V2_VALIDATION_THREADS,DEFAULT_VALIDATION_THREADS);
        return parseValidationThreadIds(csv);
    }

    public static void setValidationOverride(Context context,boolean enabled,String csvThreadIds){
        SharedPreferences p=prefs(context);
        if(p==null)return;
        p.edit()
                .putBoolean(V2_VALIDATION_OVERRIDE,enabled)
                .putString(V2_VALIDATION_THREADS,csvThreadIds==null?"":csvThreadIds)
                .apply();
    }

    static Set<Long> parseValidationThreadIds(String csv){
        LinkedHashSet<Long> ids=new LinkedHashSet<>();
        if(csv==null||csv.trim().isEmpty())return Collections.emptySet();
        String[] parts=csv.split(",");
        for(String part:parts){
            if(ids.size()>=MAX_VALIDATION_THREADS)break;
            String value=part==null?"":part.trim();
            if(value.isEmpty())continue;
            try{
                long id=Long.parseLong(value);
                if(id>0)ids.add(id);
            }catch(Throwable ignored){}
        }
        if(ids.isEmpty())return Collections.emptySet();
        return Collections.unmodifiableSet(ids);
    }

    static int clampPercent(int value){return Math.max(0,Math.min(100,value));}

    private static SharedPreferences prefs(Context context){
        return context==null?null:context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
}
