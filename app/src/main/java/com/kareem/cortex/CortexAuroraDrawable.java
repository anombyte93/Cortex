package com.kareem.cortex;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Lightweight procedural backdrop: deep graphite + cyan/violet aurora + subtle horizon grid. */
public final class CortexAuroraDrawable extends Drawable {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); private final int accentA,accentB;
    public CortexAuroraDrawable(int a,int b){accentA=a;accentB=b;}
    @Override public void draw(Canvas c){Rect b=getBounds();float w=b.width(),h=b.height();p.setShader(null);p.setColor(Color.rgb(7,9,14));c.drawRect(b,p);
        p.setShader(new RadialGradient(w*.18f,h*.06f,w*.72f,new int[]{Color.argb(105,Color.red(accentA),Color.green(accentA),Color.blue(accentA)),Color.argb(28,Color.red(accentA),Color.green(accentA),Color.blue(accentA)),Color.TRANSPARENT},new float[]{0f,.42f,1f},Shader.TileMode.CLAMP));c.drawRect(b,p);
        p.setShader(new RadialGradient(w*.92f,h*.24f,w*.78f,new int[]{Color.argb(90,Color.red(accentB),Color.green(accentB),Color.blue(accentB)),Color.argb(20,Color.red(accentB),Color.green(accentB),Color.blue(accentB)),Color.TRANSPARENT},new float[]{0f,.38f,1f},Shader.TileMode.CLAMP));c.drawRect(b,p);
        p.setShader(new LinearGradient(0,h*.50f,0,h,new int[]{Color.TRANSPARENT,Color.argb(60,0,0,0),Color.rgb(7,9,14)},null,Shader.TileMode.CLAMP));c.drawRect(b,p);
        p.setShader(null);p.setStrokeWidth(1f);p.setColor(Color.argb(11,255,255,255));float step=Math.max(42f,w/9f);for(float x=0;x<w;x+=step)c.drawLine(x,h*.52f,x,h,p);for(float y=h*.56f;y<h;y+=step)c.drawLine(0,y,w,y,p);
    }
    @Override public void setAlpha(int a){} @Override public void setColorFilter(android.graphics.ColorFilter f){} @Override public int getOpacity(){return android.graphics.PixelFormat.OPAQUE;}
}
