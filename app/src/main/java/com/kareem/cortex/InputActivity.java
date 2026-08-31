package com.kareem.cortex;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** Approved Capture Center opened by the signature + button. */
public class InputActivity extends Activity {
    int dp(int x){return CortexUi.dp(this,x);}
    @Override public void onCreate(Bundle b){super.onCreate(b);Window w=getWindow();w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(CortexUi.BG);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams lp=w.getAttributes();lp.dimAmount=.68f;w.setAttributes(lp);build();}
    @Override protected void onPostResume(){super.onPostResume();StartupMaintenance.schedule(this);}

    void build(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.TRANSPARENT);root.setOnClickListener(v->finish());
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(14),dp(10),dp(14),dp(16));panel.setBackground(CortexUi.round(this,CortexUi.BG,CortexUi.BORDER,24));panel.setOnClickListener(v->{});
        View handle=new View(this);handle.setBackground(CortexUi.round(this,CortexUi.MUTED,Color.TRANSPARENT,999));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(36),dp(3));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.setMargins(0,0,0,dp(9));panel.addView(handle,hp);
        TextView cap=CortexUi.overline(this,"CAPTURE CENTER   (Tap +)");cap.setGravity(Gravity.CENTER);cap.setTextColor(CortexUi.MUTED);panel.addView(cap,new LinearLayout.LayoutParams(-1,dp(22)));

        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);row.setPadding(0,dp(6),0,0);panel.addView(row,new LinearLayout.LayoutParams(-1,dp(98)));
        addChoice(row,"Text","Write ideas\nor notes","text","text");
        addChoice(row,"Voice","Record voice\nnotes","voice","voice");
        addChoice(row,"Photo / File","Add photo or\nany file","file","attachment");
        addChoice(row,"Link","Save any link\nor content","text","link");

        FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);pp.setMargins(dp(10),0,dp(10),dp(10));root.addView(panel,pp);setContentView(root);
    }

    void addChoice(LinearLayout row,String title,String sub,String mode,String icon){
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER);tile.setPadding(dp(3),dp(5),dp(3),dp(4));CortexUi.pressable(this,tile,CortexUi.round(this,Color.TRANSPARENT,CortexUi.BORDER_SOFT,16));
        CortexGlyphView g=CortexUi.glyph(this,icon,CortexUi.BRAND,false);tile.addView(g,new LinearLayout.LayoutParams(dp(36),dp(36)));
        TextView t=CortexUi.plain(this,title,11,CortexUi.TEXT);CortexUi.medium(t);t.setGravity(Gravity.CENTER);t.setMaxLines(1);tile.addView(t,new LinearLayout.LayoutParams(-1,dp(20)));
        TextView s=CortexUi.plain(this,sub,8,CortexUi.MUTED);s.setGravity(Gravity.CENTER);s.setMaxLines(2);tile.addView(s,new LinearLayout.LayoutParams(-1,dp(28)));
        tile.setOnClickListener(v->capture(mode));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);if(row.getChildCount()>0)p.setMargins(dp(6),0,0,0);row.addView(tile,p);
    }

    void capture(String mode){try{Intent i=new Intent(this,ProposalCaptureActivity.class);i.putExtra("mode",mode);startActivity(i);finish();}catch(Throwable ignored){}}
}
