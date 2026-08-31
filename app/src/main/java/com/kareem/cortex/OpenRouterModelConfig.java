package com.kareem.cortex;

import android.content.Context;
import android.content.SharedPreferences;

/** Runtime model selection for the OpenRouter Brain route. */
public final class OpenRouterModelConfig {
    private static final String PREFS="cortex_model_config";
    private static final String KEY="openrouter_brain_model";
    public static final String LEGACY_OX_ALPHA="stealth/ox-alpha";
    public static final String DEFAULT_MODEL="z-ai/glm-5.3";

    private OpenRouterModelConfig(){}

    public static String generationModel(Context context){
        SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String value=prefs.getString(KEY,DEFAULT_MODEL);
        String model=valid(value)?value.trim():DEFAULT_MODEL;
        // Ox Alpha was a temporary stealth preview and is retired. Preserve every
        // user-selected custom model, but migrate this one known retired default.
        if(LEGACY_OX_ALPHA.equals(model)){
            prefs.edit().putString(KEY,DEFAULT_MODEL).apply();
            return DEFAULT_MODEL;
        }
        return model;
    }

    public static boolean setGenerationModel(Context context,String model){
        String value=model==null?"":model.trim();
        if(value.isEmpty()||LEGACY_OX_ALPHA.equals(value))value=DEFAULT_MODEL;
        if(!valid(value))return false;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,value).apply();
        return true;
    }

    /** Kept for compatibility with provider code that used Ox-specific request tuning. */
    public static boolean isOxAlpha(Context context){return LEGACY_OX_ALPHA.equals(generationModel(context));}

    private static boolean valid(String value){
        if(value==null)return false;
        String x=value.trim();
        return x.length()>=3&&x.length()<=180&&x.indexOf('/')>0&&!x.matches(".*\\s+.*");
    }
}
