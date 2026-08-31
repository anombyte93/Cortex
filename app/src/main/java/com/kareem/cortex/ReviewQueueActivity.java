package com.kareem.cortex;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;

/** Human confirmation surface for uncertain Cortex interpretations. */
public class ReviewQueueActivity extends Activity {
    VaultDb db;LinearLayout list;TextView count;
    int dp(int x){return CortexUi.dp(this,x);}
    @Override public void onCreate(Bundle b){super.onCreate(b);CortexUi.applyWindow(this);db=new VaultDb(this);build();refresh();}
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onDestroy(){super.onDestroy();try{db.close();}catch(Throwable ignored){}}

    void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(CortexUi.BG);
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(18),dp(12),dp(18),dp(8));
        TextView back=CortexUi.plain(this,"‹",34,CortexUi.TEXT);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,new LinearLayout.LayoutParams(dp(42),dp(48)));
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);TextView h=CortexUi.plain(this,"Needs review",26,CortexUi.TEXT);CortexUi.medium(h);titles.addView(h);count=CortexUi.plain(this,"",11,CortexUi.MUTED);count.setPadding(0,dp(2),0,0);titles.addView(count);head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));root.addView(head);
        ScrollView sv=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(20),dp(8),dp(20),dp(28));sv.addView(list);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);CortexUi.fitSystemBars(this,root);
    }

    void refresh(){
        if(list==null)return;list.removeAllViews();ArrayList<ReviewQueueStore.Item> items=ReviewQueueStore.pending(db,100);count.setText(items.isEmpty()?"Nothing uncertain right now":items.size()+" interpretation"+(items.size()==1?"":"s")+" need your judgement");
        if(items.isEmpty()){TextView e=CortexUi.text(this,"Cortex has no uncertain action, waiting, decision or project interpretation that needs confirmation.",14,CortexUi.MUTED);e.setPadding(0,dp(22),0,0);list.addView(e);return;}
        TextView explain=CortexUi.text(this,"Confirm what Cortex understood correctly, or correct it here. Your answer becomes feedback for future similar evidence.",12,CortexUi.MUTED);explain.setPadding(dp(2),dp(2),dp(2),dp(14));list.addView(explain);
        for(ReviewQueueStore.Item x:items)addItem(x);
    }

    void addItem(ReviewQueueStore.Item x){
        LinearLayout card=CortexUi.card(this,18);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(15),dp(14),dp(15),dp(13));
        TextView type=CortexUi.plain(this,"Possible "+friendly(x.candidateKind)+"  ·  "+Math.round(x.confidence*100)+"% confidence",11,CortexUi.MUTED);CortexUi.medium(type);card.addView(type);
        TextView title=CortexUi.text(this,x.title,15,CortexUi.TEXT);CortexUi.medium(title);title.setPadding(0,dp(7),0,0);card.addView(title);
        if(!x.body.isEmpty()){TextView body=CortexUi.text(this,clip(x.body,420),13,CortexUi.TEXT);body.setPadding(0,dp(7),0,0);body.setTextIsSelectable(true);card.addView(body);}
        if(!x.reason.isEmpty()){TextView why=CortexUi.text(this,"Why Cortex is unsure: "+x.reason,11,CortexUi.MUTED);why.setPadding(0,dp(8),0,0);card.addView(why);}
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(0,dp(12),0,0);
        TextView confirm=smallAction(confirmLabel(x.candidateKind));confirm.setOnClickListener(v->{confirm(x);refresh();});actions.addView(confirm,new LinearLayout.LayoutParams(0,dp(40),1));
        TextView reject=smallAction(rejectLabel(x.candidateKind));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(40),1);rp.setMargins(dp(7),0,0,0);reject.setOnClickListener(v->{reject(x);refresh();});actions.addView(reject,rp);card.addView(actions);
        TextView dismiss=CortexUi.plain(this,"Not important / hide",11,CortexUi.MUTED);dismiss.setGravity(Gravity.CENTER);dismiss.setPadding(0,dp(8),0,0);dismiss.setOnClickListener(v->{ReviewQueueStore.notImportant(db,x.id);toast("Hidden and learned as not important");refresh();});card.addView(dismiss,new LinearLayout.LayoutParams(-1,dp(38)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(10));list.addView(card,p);
    }

    void confirm(ReviewQueueStore.Item x){
        long derived=ReviewQueueStore.confirm(db,x.id);if(derived<=0){toast("Could not confirm");return;}
        if("PROJECT_CANDIDATE".equals(x.candidateKind)){
            boolean created=ProjectCandidateStore.confirm(db,derived);
            toast(created?"Project confirmed":"Saved as a project candidate; its name still needs review");
        }else toast("Confirmed");
    }
    void reject(ReviewQueueStore.Item x){
        boolean ok;if("ACTION".equals(x.candidateKind))ok=ReviewQueueStore.notAction(db,x.id);else ok=ReviewQueueStore.dismiss(db,x.id);
        toast(ok?rejectFeedback(x.candidateKind):"Could not save correction");
    }
    String confirmLabel(String kind){String k=kind==null?"":kind.toUpperCase(Locale.US);if("PROJECT_CANDIDATE".equals(k))return"Yes, project";if("ACTION".equals(k))return"Yes, I owe this";if("WAITING".equals(k))return"Yes, waiting";if("DECISION".equals(k))return"Yes, decision";return"Confirm";}
    String rejectLabel(String kind){String k=kind==null?"":kind.toUpperCase(Locale.US);if("PROJECT_CANDIDATE".equals(k))return"Not a project";if("ACTION".equals(k))return"Not my action";if("WAITING".equals(k))return"Not waiting";if("DECISION".equals(k))return"Not a decision";return"Not this";}
    String rejectFeedback(String kind){String k=kind==null?"":kind.toUpperCase(Locale.US);if("ACTION".equals(k))return"Learned: not your action";if("WAITING".equals(k))return"Learned: not a waiting item";if("DECISION".equals(k))return"Learned: not a decision";if("PROJECT_CANDIDATE".equals(k))return"Learned: not a project";return"Correction saved";}
    TextView smallAction(String s){TextView v=CortexUi.plain(this,s,12,CortexUi.TEXT);CortexUi.medium(v);v.setGravity(Gravity.CENTER);CortexUi.pressable(this,v,CortexUi.round(this,CortexUi.SURFACE_2,android.graphics.Color.TRANSPARENT,13));return v;}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    String friendly(String x){String k=x==null?"":x.toLowerCase(Locale.US).replace('_',' ');return k.isEmpty()?"item":k;}
    String clip(String s,int n){return s.length()<=n?s:s.substring(0,n)+"…";}
}