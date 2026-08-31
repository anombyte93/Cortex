package com.kareem.cortex;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;

/** Intentional capture stream: save first, understand and connect asynchronously. */
public final class InboxActivity extends Activity {
    private VaultDb db;private LinearLayout list;private EditText composer;

    @Override protected void onCreate(Bundle state){super.onCreate(state);CortexUi.applyWindow(this);db=new VaultDb(this);build();render();focusComposerIfRequested(getIntent());}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);focusComposerIfRequested(intent);}
    @Override protected void onDestroy(){super.onDestroy();try{db.close();}catch(Throwable ignored){}}

    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(CortexUi.aurora(this));root.setPadding(CortexUi.dp(this,18),CortexUi.dp(this,10),CortexUi.dp(this,18),0);
        TextView eyebrow=CortexUi.plain(this,"PERSONAL INTAKE",10,CortexUi.BRAND);CortexUi.medium(eyebrow);if(android.os.Build.VERSION.SDK_INT>=21)eyebrow.setLetterSpacing(.13f);root.addView(eyebrow,new LinearLayout.LayoutParams(-1,CortexUi.dp(this,24)));
        TextView title=CortexUi.plain(this,"Inbox",32,CortexUi.TEXT);CortexUi.bold(title);root.addView(title,new LinearLayout.LayoutParams(-1,CortexUi.dp(this,44)));
        TextView sub=CortexUi.text(this,"Throw anything at Cortex. It keeps the original, understands it, and quietly connects it to the rest of your world.",12,CortexUi.MUTED);root.addView(sub,new LinearLayout.LayoutParams(-1,CortexUi.dp(this,50)));
        LinearLayout mode=CortexUi.card(this,20);mode.setOrientation(LinearLayout.HORIZONTAL);mode.setGravity(Gravity.CENTER_VERTICAL);mode.setPadding(CortexUi.dp(this,12),CortexUi.dp(this,10),CortexUi.dp(this,12),CortexUi.dp(this,10));TextView orb=CortexUi.plain(this,"●",19,CortexUi.BRAND);mode.addView(orb,new LinearLayout.LayoutParams(CortexUi.dp(this,32),CortexUi.dp(this,32)));LinearLayout mt=new LinearLayout(this);mt.setOrientation(LinearLayout.VERTICAL);TextView mh=CortexUi.plain(this,"Save first. Think second.",14,CortexUi.TEXT);CortexUi.medium(mh);mt.addView(mh);TextView mb=CortexUi.plain(this,"Nothing gets lost because AI or a link fetch failed.",10,CortexUi.MUTED);mb.setPadding(0,CortexUi.dp(this,2),0,0);mt.addView(mb);mode.addView(mt,new LinearLayout.LayoutParams(0,-2,1));TextView safe=CortexUi.chip(this,"SAFE",CortexUi.GREEN,true);mode.addView(safe,new LinearLayout.LayoutParams(-2,CortexUi.dp(this,28)));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.setMargins(0,CortexUi.dp(this,5),0,CortexUi.dp(this,9));root.addView(mode,mp);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,CortexUi.dp(this,2),0,CortexUi.dp(this,8));scroll.addView(list,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout sendShell=CortexUi.card(this,22);sendShell.setPadding(CortexUi.dp(this,7),CortexUi.dp(this,5),CortexUi.dp(this,5),CortexUi.dp(this,5));sendShell.setOrientation(LinearLayout.HORIZONTAL);sendShell.setGravity(Gravity.CENTER_VERTICAL);composer=new EditText(this);composer.setHint("Drop a thought, link, reminder, idea…");composer.setHintTextColor(CortexUi.FAINT);composer.setTextColor(CortexUi.TEXT);composer.setTextSize(14);composer.setSingleLine(false);composer.setMaxLines(3);composer.setBackgroundColor(Color.TRANSPARENT);composer.setPadding(CortexUi.dp(this,10),CortexUi.dp(this,9),CortexUi.dp(this,10),CortexUi.dp(this,9));sendShell.addView(composer,new LinearLayout.LayoutParams(0,CortexUi.dp(this,52),1));TextView send=CortexUi.action(this,"Send",CortexUi.BRAND,true);sendShell.addView(send,new LinearLayout.LayoutParams(CortexUi.dp(this,72),CortexUi.dp(this,44)));send.setOnClickListener(v->saveComposer());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,CortexUi.dp(this,5),0,CortexUi.dp(this,4));root.addView(sendShell,cp);
        CortexUi.addBottomNav(this,root,"inbox",null);setContentView(root);CortexUi.fitSystemBars(this,root);
    }

    private void focusComposerIfRequested(Intent i){if(i==null||!i.getBooleanExtra("focus_composer",false)||composer==null)return;composer.postDelayed(()->{composer.requestFocus();try{InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);if(imm!=null)imm.showSoftInput(composer,InputMethodManager.SHOW_IMPLICIT);}catch(Throwable ignored){}},180);}
    private void saveComposer(){String text=composer.getText()==null?"":composer.getText().toString().trim();if(text.isEmpty())return;long id=InboxStore.addNote(db,text);if(id>0){composer.setText("");AnalysisQueue.kick(this,null,this::render);render();}}

    private void render(){if(list==null)return;runOnUiThread(()->{list.removeAllViews();ArrayList<KnowledgeItem> items=InboxStore.recent(db,120);if(items.isEmpty()){LinearLayout e=CortexUi.card(this,22);TextView h=CortexUi.plain(this,"Nothing waiting in the stream",18,CortexUi.TEXT);CortexUi.medium(h);e.addView(h);TextView b=CortexUi.text(this,"Share from any app or type below. Cortex will keep the capture intact and do the filing for you.",12,CortexUi.MUTED);b.setPadding(0,CortexUi.dp(this,6),0,0);e.addView(b);list.addView(e,params());return;}HashSet<String> seen=new HashSet<>();int shown=0;for(KnowledgeItem k:items){String sig=signature(k);if(!sig.isEmpty()&&seen.contains(sig))continue;if(!sig.isEmpty())seen.add(sig);list.addView(card(k),params());shown++;if(shown>=48)break;}});}

    private View card(KnowledgeItem k){LinearLayout c=CortexUi.card(this,20);c.setPadding(CortexUi.dp(this,14),CortexUi.dp(this,12),CortexUi.dp(this,14),CortexUi.dp(this,11));String state=InboxStore.processingState(k);LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);View dot=new View(this);int sc=stateColor(state);dot.setBackground(CortexUi.round(this,sc,Color.TRANSPARENT,999));top.addView(dot,new LinearLayout.LayoutParams(CortexUi.dp(this,7),CortexUi.dp(this,7)));String heading=cleanHeading(k);TextView h=CortexUi.plain(this,heading,15,CortexUi.TEXT);CortexUi.medium(h);h.setMaxLines(2);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);hp.setMargins(CortexUi.dp(this,9),0,CortexUi.dp(this,5),0);top.addView(h,hp);TextView stateChip=CortexUi.chip(this,shortState(state),sc,false);top.addView(stateChip,new LinearLayout.LayoutParams(-2,CortexUi.dp(this,27)));c.addView(top);String body=bestText(k);if(!body.isEmpty()){TextView b=CortexUi.text(this,trim(body,220),11,CortexUi.MUTED);b.setPadding(0,CortexUi.dp(this,7),0,0);b.setMaxLines(4);c.addView(b);}LinearLayout meta=new LinearLayout(this);meta.setGravity(Gravity.CENTER_VERTICAL);String time=DateFormat.format("h:mm a",k.createdAt).toString();TextView m=CortexUi.plain(this,time+"  ·  "+friendlyState(state),9,CortexUi.MUTED);meta.addView(m,new LinearLayout.LayoutParams(0,CortexUi.dp(this,27),1));if("fetch_failed".equals(state)){TextView retry=CortexUi.chip(this,"Retry",CortexUi.ORANGE,false);retry.setOnClickListener(v->{try{SharedLinkIntelligence.enrichAsync(this,db,k.id,k.rawText);render();}catch(Throwable ignored){}});meta.addView(retry,new LinearLayout.LayoutParams(-2,CortexUi.dp(this,27)));}c.addView(meta);return c;}

    private LinearLayout.LayoutParams params(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,CortexUi.dp(this,8));return p;}
    private String cleanHeading(KnowledgeItem k){String raw=k.title==null?"":k.title.trim();if(raw.isEmpty()||looksLikeBootstrap(raw)){String host=hostLabel(k.rawText);if(!host.isEmpty())return host;return friendlyType(k.type);}return trim(raw,70);}
    private String friendlyType(String s){if(s==null)return"Saved item";String x=s.toLowerCase().replace('_',' ');return x.isEmpty()?"Saved item":Character.toUpperCase(x.charAt(0))+x.substring(1);}
    private String friendlyState(String s){if("understood".equals(s)||"analyzed".equals(s))return"Understood and ready to connect";if("fetch_failed".equals(s))return"Content unavailable · original preserved";if("analysis_failed".equals(s))return"Understanding failed · original preserved";if("pending_content".equals(s)||"queued".equals(s)||"analyzing".equals(s))return"Cortex is understanding this";return"Captured safely";}
    private String shortState(String s){if("understood".equals(s)||"analyzed".equals(s))return"READY";if("fetch_failed".equals(s)||"analysis_failed".equals(s))return"PRESERVED";if("pending_content".equals(s)||"queued".equals(s)||"analyzing".equals(s))return"THINKING";return"SAVED";}
    private String bestText(KnowledgeItem k){String[] candidates=new String[]{k.summary,k.extractedText,k.rawText};for(String x:candidates){String cleaned=cleanPayload(x);if(!cleaned.isEmpty())return cleaned;}return"";}
    private String cleanPayload(String s){if(s==null)return"";String x=s.trim();if(x.isEmpty())return"";if(looksLikeBootstrap(x)){String host=hostLabel(x);return host.isEmpty()?"Shared web content saved. Open the item for the original source.":host+" · Shared content saved and preserved.";}return x.replaceAll("\\s+"," ").trim();}
    private boolean looksLikeBootstrap(String s){if(s==null)return false;String x=s.toLowerCase();return x.contains("scheduledserverjs")||x.contains("__bbox")||x.contains("__rc")||x.contains("require:[")||x.contains("instagram {\"require\"")||x.length()>1800&&x.contains("null,null");}
    private String hostLabel(String s){if(s==null)return"";String x=s.toLowerCase();if(x.contains("instagram.com")||x.contains("instagram"))return"Instagram";if(x.contains("facebook.com")||x.contains("facebook"))return"Facebook";if(x.contains("youtube.com")||x.contains("youtu.be"))return"YouTube";if(x.contains("tiktok.com"))return"TikTok";if(x.contains("x.com/")||x.contains("twitter.com"))return"X / Twitter";return"";}
    private String signature(KnowledgeItem k){String host=hostLabel((k.rawText==null?"":k.rawText)+" "+(k.title==null?"":k.title));String body=bestText(k).toLowerCase().replaceAll("\\s+"," ").trim();if(!host.isEmpty()&&looksLikeBootstrap(k.rawText))return host+"|bootstrap";if(body.length()>120)body=body.substring(0,120);return (host+"|"+body).trim();}
    private int stateColor(String s){if("understood".equals(s)||"analyzed".equals(s))return CortexUi.GREEN;if("fetch_failed".equals(s)||"analysis_failed".equals(s))return CortexUi.ORANGE;if("pending_content".equals(s)||"queued".equals(s)||"analyzing".equals(s))return CortexUi.BRAND;return CortexUi.AURORA;}
    private String trim(String s,int n){return s.length()<=n?s:s.substring(0,n)+"…";}
}
