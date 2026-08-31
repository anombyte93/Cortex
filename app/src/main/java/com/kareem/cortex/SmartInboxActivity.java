package com.kareem.cortex;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Focus contains only three queues: Needs, Waiting and Actions. */
public class SmartInboxActivity extends Activity {
    VaultDb db;LinearLayout feed,tabs;TextView tabNeeds,tabWaiting,tabActions;String mode="needs";volatile int generation=0;
    int dp(int x){return CortexUi.dp(this,x);}
    @Override public void onCreate(Bundle b){super.onCreate(b);CortexUi.applyWindow(this);db=new VaultDb(this);FeatureStore.ensure(db);String m=getIntent().getStringExtra("mode");if("waiting".equals(m)||"action".equals(m)||"needs".equals(m))mode=m;build();refreshAsync();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);String m=i.getStringExtra("mode");if("waiting".equals(m)||"action".equals(m)||"needs".equals(m))mode=m;styleTabs();refreshAsync();}
    @Override protected void onResume(){super.onResume();if(db!=null)refreshAsync();}

    void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(CortexUi.BG);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),0);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);TextView h=CortexUi.plain(this,"Focus",29,CortexUi.TEXT);CortexUi.medium(h);head.addView(h,new LinearLayout.LayoutParams(0,-2,1));TextView settings=CortexUi.chip(this,"Settings",CortexUi.MUTED,false);settings.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));head.addView(settings,new LinearLayout.LayoutParams(-2,dp(36)));body.addView(head);
        tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);tabs.setPadding(0,dp(16),0,dp(10));tabNeeds=tab("Needs","needs");tabWaiting=tab("Waiting","waiting");tabActions=tab("Actions","action");body.addView(tabs,new LinearLayout.LayoutParams(-1,dp(62)));
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);feed=new LinearLayout(this);feed.setOrientation(LinearLayout.VERTICAL);feed.setPadding(0,0,0,dp(20));sv.addView(feed);body.addView(sv,new LinearLayout.LayoutParams(-1,0,1));CortexUi.addBottomNav(this,root,"focus",null);setContentView(root);styleTabs();}

    TextView tab(String label,String key){TextView t=CortexUi.chip(this,label,CortexUi.MUTED,false);t.setOnClickListener(v->{if(key.equals(mode))return;mode=key;styleTabs();refreshAsync();});t.setTag(label);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.setMargins(0,0,dp("action".equals(key)?0:8),0);tabs.addView(t,p);return t;}
    void styleTabs(){TextView[] views={tabNeeds,tabWaiting,tabActions};String[] keys={"needs","waiting","action"};for(int i=0;i<views.length;i++){boolean on=keys[i].equals(mode);views[i].setTextColor(on?CortexUi.TEXT:CortexUi.MUTED);views[i].setBackground(CortexUi.round(this,on?CortexUi.SURFACE_2:Color.TRANSPARENT,on?CortexUi.ACCENT:Color.TRANSPARENT,999));if(on)CortexUi.medium(views[i]);}}

    void refreshAsync(){final int g=++generation;new Thread(()->{try{ArrayList<FeatureStore.InboxEntry> needs=FeatureStore.needs(db,120),inbox=FeatureStore.inbox(db,140);LinkedHashMap<Long,FeatureStore.InboxEntry> waiting=new LinkedHashMap<>(),actions=new LinkedHashMap<>();mergeBucket(waiting,needs,"Waiting");mergeBucket(waiting,inbox,"Waiting");mergeBucket(actions,needs,"Action");mergeBucket(actions,inbox,"Action");ArrayList<FeatureStore.InboxEntry> visible="waiting".equals(mode)?new ArrayList<>(waiting.values()):"action".equals(mode)?new ArrayList<>(actions.values()):needs;runOnUiThread(()->{if(g!=generation||isFinishing())return;tabNeeds.setText("Needs ("+needs.size()+")");tabWaiting.setText("Waiting ("+waiting.size()+")");tabActions.setText("Actions ("+actions.size()+")");styleTabs();render(visible);});}catch(Throwable e){runOnUiThread(()->{if(g==generation)showError(e);});}},"CortexFocusRefresh").start();}
    void mergeBucket(LinkedHashMap<Long,FeatureStore.InboxEntry> out,ArrayList<FeatureStore.InboxEntry> xs,String bucket){for(FeatureStore.InboxEntry e:xs)if(bucket.equals(e.bucket))out.put(e.item.id,e);}

    void render(ArrayList<FeatureStore.InboxEntry> xs){feed.removeAllViews();if(xs.isEmpty()){emptyState();return;}for(FeatureStore.InboxEntry e:xs)addEntry(e);}
    void emptyState(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER);c.setPadding(dp(22),dp(52),dp(22),dp(52));TextView mark=CortexUi.plain(this,"✓",28,CortexUi.SAGE);mark.setGravity(Gravity.CENTER);c.addView(mark);String t="needs".equals(mode)?"Nothing needs you":"waiting".equals(mode)?"Nothing waiting":"No open actions";TextView h=CortexUi.plain(this,t,18,CortexUi.TEXT);CortexUi.medium(h);h.setGravity(Gravity.CENTER);h.setPadding(0,dp(8),0,0);c.addView(h);feed.addView(c);}
    void showError(Throwable e){feed.removeAllViews();TextView t=CortexUi.text(this,"Could not refresh Focus.",12,CortexUi.MUTED);t.setPadding(dp(2),dp(20),dp(2),0);feed.addView(t);}

    void addEntry(FeatureStore.InboxEntry e){
        ArrayList<String> rawActions=FeatureStore.openActions(db,e.item.id),actions=InboxPresentation.actionParts(rawActions);
        String semanticTitle=InboxPresentation.title(e.item,rawActions),preview=InboxPresentation.preview(e.item,rawActions,semanticTitle);
        String state=InboxPresentation.stateLabel(e.item,e.bucket,e.pinned,e.reviewed,rawActions),signal=InboxPresentation.signalLabel(e.item,e.score,rawActions),due=InboxPresentation.due(rawActions);

        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(2),dp(15),dp(2),dp(14));
        TextView title=CortexUi.text(this,semanticTitle,16,CortexUi.TEXT);CortexUi.medium(title);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);directional(title);c.addView(title);
        TextView meta=CortexUi.plain(this,friendly(e.item.type)+"  •  "+age(e.item.createdAt)+"  •  "+state+"  •  "+signal,10,CortexUi.MUTED);meta.setPadding(0,dp(5),0,0);directional(meta);c.addView(meta);
        if(!empty(due)){TextView d=CortexUi.plain(this,"Due · "+due,10,CortexUi.ACCENT);d.setPadding(0,dp(5),0,0);directional(d);c.addView(d);}
        if(!empty(preview)){TextView p=CortexUi.text(this,clip(preview,210),13,CortexUi.TEXT);p.setMaxLines(4);p.setPadding(0,dp(9),0,0);directional(p);c.addView(p);}
        int shown=0;for(String action:actions){if(InboxPresentation.sameMeaning(action,semanticTitle))continue;TextView a=CortexUi.text(this,"• "+action,12,CortexUi.TEXT);a.setPadding(0,dp(shown==0?10:2),0,dp(2));directional(a);c.addView(a);if(++shown>=3)break;}
        if(!empty(e.reason)){TextView why=CortexUi.plain(this,shortReason(e.reason),10,CortexUi.FAINT);why.setPadding(0,dp(7),0,0);directional(why);c.addView(why);}

        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);controls.setPadding(0,dp(12),0,0);boolean failed="failed_retryable".equals(e.item.status)||"analysis_failed".equals(e.item.status);TextView primary=CortexUi.action(this,failed?"Retry":rawActions.isEmpty()?"Dismiss":"Done",failed?CortexUi.ACCENT:rawActions.isEmpty()?CortexUi.MUTED:CortexUi.SAGE,false);TextView snooze=CortexUi.action(this,"Snooze",CortexUi.MUTED,false);controls.addView(primary,new LinearLayout.LayoutParams(0,dp(42),1));LinearLayout.LayoutParams snoozeParams=new LinearLayout.LayoutParams(0,dp(42),1);snoozeParams.setMargins(dp(8),0,0,0);controls.addView(snooze,snoozeParams);c.addView(controls);
        primary.setOnClickListener(v->{if(failed){FeatureStore.resetAttention(db,e.item.id);db.retry(e.item.id);AnalysisQueue.kick(this,db,this::refreshAsync);}else if(rawActions.isEmpty())FeatureStore.dismissAttention(db,e.item.id);else FeatureStore.markDone(db,e.item.id);refreshAsync();});
        snooze.setOnClickListener(v->{FeatureStore.snooze(db,e.item.id,System.currentTimeMillis()+24L*60*60*1000);refreshAsync();});
        c.setOnClickListener(v->{Intent i=new Intent(this,VaultActivity.class);i.putExtra("item_id",e.item.id);startActivity(i);});feed.addView(c);feed.addView(CortexUi.divider(this),new LinearLayout.LayoutParams(-1,dp(1)));
    }

    void directional(TextView v){v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);v.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);}
    String shortReason(String s){String x=s.replace(" • "," · ");return x.length()>76?x.substring(0,76)+"…":x;}
    String friendly(String s){if("AUDIO".equals(s))return"Voice";if("FILE".equals(s))return"File";if("SCREENSHOT".equals(s)||"IMAGE".equals(s))return"Image";return"Memory";}
    String clip(String s,int n){return s==null?"":s.length()<=n?s:s.substring(0,n)+"…";}
    boolean empty(String s){return s==null||s.trim().isEmpty();}
    String age(long ms){long m=Math.max(0,System.currentTimeMillis()-ms)/60000;if(m<1)return"now";if(m<60)return m+"m";long h=m/60;if(h<24)return h+"h";long d=h/24;return d<7?d+"d":new SimpleDateFormat("dd MMM",Locale.getDefault()).format(new Date(ms));}
}
