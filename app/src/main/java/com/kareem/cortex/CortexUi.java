package com.kareem.cortex;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.*;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Approved Cortex AMOLED system: near-black, restrained surfaces, semantic accents. */
public final class CortexUi {
    public static final int BG=Color.rgb(10,10,9);
    public static final int SURFACE=Color.rgb(26,28,25);
    public static final int SURFACE_2=SURFACE;
    public static final int SURFACE_3=SURFACE;
    public static final int TEXT=Color.rgb(245,247,241);
    public static final int MUTED=Color.rgb(161,163,154);
    public static final int FAINT=Color.argb(138,161,163,154);

    // Canonical CardVariant / StatusDot semantic palette retained across V2 reconciliation.
    public static final int BRAND=Color.rgb(137,217,74);   // #89D94A insight / connected
    public static final int ORANGE=Color.rgb(229,169,59);  // #E5A93B active / needs attention
    public static final int QUIET=Color.rgb(51,53,50);     // #333532 quiet
    public static final int PURPLE=Color.rgb(155,81,224);  // #9B51E0 project / archived
    public static final int RED=Color.rgb(217,83,79);      // #D9534F needs review
    public static final int BLUE=Color.rgb(74,144,226);    // #4A90E2 recently added
    public static final int GREEN=BRAND;
    public static final int AURORA=ORANGE;
    public static final int YELLOW=ORANGE;
    public static final int LIME=BRAND,OLIVE=AURORA;

    public static final int ACCENT=BRAND,SIGNAL=BRAND,AMBER=ORANGE,SAGE=GREEN,INFO=BRAND,VIOLET=PURPLE,COPPER=ORANGE,CORAL=RED,GOLD=AURORA;
    public static final int BORDER=Color.argb(84,161,163,154),BORDER_SOFT=Color.argb(45,161,163,154),HAIRLINE=Color.argb(36,161,163,154);

    private CortexUi(){}
    public static int dp(Activity a,int v){return(int)(v*a.getResources().getDisplayMetrics().density+.5f);}
    public static Drawable aurora(Activity a){return round(a,BG,Color.TRANSPARENT,0);}

    public static void applyWindow(Activity a){
        Window w=a.getWindow();w.setStatusBarColor(BG);w.setNavigationBarColor(BG);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=29)w.setNavigationBarContrastEnforced(false);if(Build.VERSION.SDK_INT>=23)w.getDecorView().setSystemUiVisibility(0);
    }
    public static void fitSystemBars(Activity a,View root){
        final int pl=root.getPaddingLeft(),pt=root.getPaddingTop(),pr=root.getPaddingRight(),pb=root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v,insets)->{int l=0,t=0,r=0,b=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());l=sys.left;t=sys.top;r=sys.right;b=Math.max(sys.bottom,ime.bottom);}else{l=insets.getSystemWindowInsetLeft();t=insets.getSystemWindowInsetTop();r=insets.getSystemWindowInsetRight();b=insets.getSystemWindowInsetBottom();}v.setPadding(pl+l,pt+t,pr+r,pb+b);return insets;});root.requestApplyInsets();
    }

    public static GradientDrawable round(Activity a,int fill,int stroke,int radius){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(a,radius));if(stroke!=Color.TRANSPARENT)g.setStroke(dp(a,1),stroke);return g;}
    public static GradientDrawable gradient(Activity a,int start,int end,int stroke,int radius){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{start,end});g.setCornerRadius(dp(a,radius));if(stroke!=Color.TRANSPARENT)g.setStroke(dp(a,1),stroke);return g;}
    public static GradientDrawable velvet(Activity a,int radius){return round(a,SURFACE,HAIRLINE,radius);}
    public static GradientDrawable matte(Activity a,int radius){return round(a,SURFACE,HAIRLINE,radius);}
    public static View divider(Activity a){View v=new View(a);v.setBackgroundColor(BORDER_SOFT);return v;}
    public static<T extends View>T raised(Activity a,T v,int e){if(Build.VERSION.SDK_INT>=21){v.setElevation(dp(a,Math.min(e,4)));v.setTranslationZ(0f);}return v;}
    public static CortexGlyphView glyph(Activity a,String k,int c,boolean d){return new CortexGlyphView(a,k,c,d);}

    public static View pressable(Activity a,View v,GradientDrawable base){
        if(Build.VERSION.SDK_INT>=21)v.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(28,137,217,74)),base,null));else v.setBackground(base);
        v.setClickable(true);v.setFocusable(true);v.setOnTouchListener((x,e)->{if(Build.VERSION.SDK_INT>=21){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x.setScaleX(.985f);x.setScaleY(.985f);}else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){x.animate().scaleX(1f).scaleY(1f).setDuration(100).start();}}return false;});return v;
    }

    public static TextView text(Activity a,String s,int sp,int c){TextView v=new TextView(a);v.setTextSize(sp);v.setTextColor(c);v.setIncludeFontPadding(false);v.setLineSpacing(0,1.08f);v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));CortexTextUi.setReadable(v,s==null?"":s);return v;}
    public static TextView plain(Activity a,String s,int sp,int c){TextView v=new TextView(a);v.setTextSize(sp);v.setTextColor(c);v.setIncludeFontPadding(false);v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));CortexTextUi.setPlain(v,s==null?"":s);return v;}
    public static void medium(TextView v){v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));}
    public static void bold(TextView v){v.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));}
    public static TextView display(Activity a,String s){TextView v=text(a,s,24,BRAND);medium(v);return v;}
    public static TextView sectionTitle(Activity a,String s){TextView v=text(a,s,18,TEXT);medium(v);return v;}
    public static TextView cardTitle(Activity a,String s){TextView v=text(a,s,16,TEXT);medium(v);return v;}
    public static TextView body(Activity a,String s){return text(a,s,14,TEXT);}
    public static TextView caption(Activity a,String s){return text(a,s,12,MUTED);}
    public static TextView overline(Activity a,String s){TextView v=plain(a,s,10,MUTED);medium(v);return v;}

    public static LinearLayout card(Activity a,int r){LinearLayout c=new LinearLayout(a);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14));c.setBackground(velvet(a,r));return c;}
    public static TextView chip(Activity a,String l,int c,boolean strong){TextView v=plain(a,l,strong?12:11,strong?TEXT:c);if(strong)medium(v);v.setGravity(Gravity.CENTER);v.setPadding(dp(a,11),0,dp(a,11),0);int fill=Color.argb(strong?24:12,Color.red(c),Color.green(c),Color.blue(c));int stroke=Color.argb(strong?72:46,Color.red(c),Color.green(c),Color.blue(c));v.setBackground(round(a,fill,stroke,10));return v;}
    public static TextView section(Activity a,String t){TextView h=overline(a,t);h.setPadding(0,dp(a,22),0,dp(a,9));return h;}
    public static TextView action(Activity a,String l,int c,boolean filled){TextView b=plain(a,l,14,filled?BG:c);medium(b);b.setGravity(Gravity.CENTER);b.setPadding(dp(a,14),0,dp(a,14),0);GradientDrawable base=filled?round(a,BRAND,Color.argb(62,245,247,241),14):round(a,Color.argb(10,Color.red(c),Color.green(c),Color.blue(c)),Color.argb(60,Color.red(c),Color.green(c),Color.blue(c)),14);pressable(a,b,base);return b;}
    public static int semanticFor(String key){if(key==null)return BRAND;String k=key.toLowerCase();if(k.contains("review")||k.contains("urgent")||k.contains("error"))return RED;if(k.contains("attention")||k.contains("wait")||k.contains("remind")||k.contains("active"))return ORANGE;if(k.contains("new")||k.contains("recent"))return BLUE;if(k.contains("project")||k.contains("archive")||k.contains("inactive"))return PURPLE;if(k.contains("quiet"))return QUIET;if(k.contains("connected")||k.contains("complete")||k.contains("good")||k.contains("insight"))return GREEN;return BRAND;}

    /** Approved reference dock: compact, quiet, icon-led. */
    public static void addBottomNav(Activity a,LinearLayout root,String selected,Runnable ignored){
        String current=primeKey(selected);LinearLayout bar=new LinearLayout(a);bar.setGravity(Gravity.CENTER);bar.setPadding(dp(a,8),dp(a,4),dp(a,8),dp(a,4));bar.setBackground(round(a,BG,BORDER,24));
        addNav(a,bar,"today","Now","nav_clock",current,CompactTodayActivity.class);addNav(a,bar,"inbox","Inbox","nav_inbox",current,InboxActivity.class);addCapture(a,bar);addNav(a,bar,"library","Atlas","nav_atlas",current,ProposalPeopleProjectsActivity.class);addNav(a,bar,"ask","Ask","nav_brain",current,ProposalAskCortexActivity.class);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(a,72));p.setMargins(dp(a,14),dp(a,3),dp(a,14),dp(a,8));root.addView(bar,p);fitSystemBars(a,root);
    }
    private static void addCapture(Activity a,LinearLayout bar){
        LinearLayout outer=new LinearLayout(a);outer.setGravity(Gravity.CENTER);outer.setBackground(round(a,Color.rgb(14,15,13),Color.argb(86,137,217,74),999));
        TextView plus=plain(a,"+",35,BG);plus.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));plus.setGravity(Gravity.CENTER);pressable(a,plus,round(a,BRAND,Color.argb(110,245,247,241),999));raised(a,outer,3);
        plus.setOnClickListener(x->{try{Intent i=new Intent(a,InputActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);a.startActivity(i);}catch(Throwable ignored){}});
        outer.addView(plus,new LinearLayout.LayoutParams(dp(a,58),dp(a,58)));LinearLayout wrap=new LinearLayout(a);wrap.setGravity(Gravity.CENTER);wrap.addView(outer,new LinearLayout.LayoutParams(dp(a,64),dp(a,64)));bar.addView(wrap,new LinearLayout.LayoutParams(dp(a,72),-1));
    }
    private static String primeKey(String s){if("home".equals(s)||"focus".equals(s)||"brief".equals(s)||"today".equals(s)||"now".equals(s))return"today";if("vault".equals(s)||"people".equals(s)||"memory".equals(s)||"world".equals(s)||"projects".equals(s)||"library".equals(s)||"atlas".equals(s))return"library";if("ask".equals(s)||"brain".equals(s)||"cortex".equals(s))return"ask";if("input".equals(s)||"capture".equals(s)||"inbox".equals(s))return"inbox";return s==null?"":s;}
    private static void addNav(Activity a,LinearLayout bar,String key,String label,String glyph,String selected,Class<?> target){boolean on=key.equals(selected);LinearLayout item=new LinearLayout(a);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);CortexGlyphView g=glyph(a,glyph,on?BRAND:MUTED,false);item.addView(g,new LinearLayout.LayoutParams(dp(a,31),dp(a,31)));TextView t=plain(a,label,10,on?BRAND:MUTED);if(on)medium(t);t.setGravity(Gravity.CENTER);item.addView(t,new LinearLayout.LayoutParams(-1,dp(a,17)));if(on){View line=new View(a);line.setBackground(round(a,BRAND,Color.TRANSPARENT,999));item.addView(line,new LinearLayout.LayoutParams(dp(a,26),dp(a,2)));}if(!on)item.setOnClickListener(x->{try{Intent i=new Intent(a,target);i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);a.startActivity(i);}catch(Throwable ignored){}});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(a,1),0,dp(a,1),0);bar.addView(item,p);}
}
