package app.voiceflow;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** The same Daybook timeline as Dictation, with local-only expiring clipboard entries. */
final class ClipboardCard {
    private static final int PAGE=50;
    private final Set<String> openedDays=new HashSet<>(),closedDays=new HashSet<>();
    private final Set<Long> openedEntries=new HashSet<>();
    private View expiryHost;private Runnable expiryRefresh;
    void restore(Bundle state){if(state==null)return;ArrayList<String> open=state.getStringArrayList("clipboard_days_open"),closed=state.getStringArrayList("clipboard_days_closed");if(open!=null)openedDays.addAll(open);if(closed!=null)closedDays.addAll(closed);long[] entries=state.getLongArray("clipboard_entries_open");if(entries!=null)for(long id:entries)openedEntries.add(id);}
    void save(Bundle state){state.putStringArrayList("clipboard_days_open",new ArrayList<>(openedDays));state.putStringArrayList("clipboard_days_closed",new ArrayList<>(closedDays));long[] entries=new long[openedEntries.size()];int at=0;for(long id:openedEntries)entries[at++]=id;state.putLongArray("clipboard_entries_open",entries);}
    void stop(){if(expiryHost!=null&&expiryRefresh!=null)expiryHost.removeCallbacks(expiryRefresh);expiryHost=null;expiryRefresh=null;}
    void show(Context context,LinearLayout host,Runnable refresh){
        stop();List<ClipboardArchive.Day> days=ClipList.days(context);
        host.addView(Daybook.text(context,"Copies stay on this phone for 30 days.",12,Daybook.muted(context),false));Ui.space(host,12);
        if(!ClipList.error().isEmpty()){host.addView(Daybook.text(context,ClipList.error(),13,Daybook.coral(context),false));Ui.space(host,10);}
        if(days.isEmpty()){host.addView(Daybook.text(context,"Android shares copies while VonoTation is open or selected as your keyboard.",13,Daybook.muted(context),false));return;}
        String today=ClipboardRetention.key(System.currentTimeMillis(),TimeZone.getDefault());java.util.Calendar yesterday=java.util.Calendar.getInstance();yesterday.add(java.util.Calendar.DAY_OF_YEAR,-1);String prior=ClipboardRetention.key(yesterday.getTimeInMillis(),yesterday.getTimeZone());
        int index=0;
        for(ClipboardArchive.Day day:days){
            LinearLayout section=Ui.row(context);section.setGravity(Gravity.TOP);section.addView(new Daybook.Rail(context,index++==0),new LinearLayout.LayoutParams(Ui.dp(context,18),-1));LinearLayout content=Ui.column(context);content.setPadding(Ui.dp(context,3),0,0,Ui.dp(context,19));section.addView(content,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout head=Ui.row(context);head.setMinimumHeight(Ui.dp(context,44));head.setBackground(Daybook.ripple(context,android.graphics.Color.TRANSPARENT,8));LinearLayout labels=Ui.column(context);
            String title=day.legacy?"Earlier copies":day.key.equals("other_dates")?"Other dates":day.key.equals(today)?"Today":day.key.equals(prior)?"Yesterday":new SimpleDateFormat("EEEE",Locale.getDefault()).format(new Date(day.time));
            labels.addView(Daybook.text(context,title,15,Daybook.ink(context),true));Ui.space(labels,3);
            String subtitle=(day.legacy?"Date unavailable":day.key.equals("other_dates")?"Device clock changed":new SimpleDateFormat("EEE, MMM d",Locale.getDefault()).format(new Date(day.time)))+" · "+day.count+(day.count==1?" copy":" copies");
            labels.addView(Daybook.text(context,subtitle,11,Daybook.muted(context),false));head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=Daybook.text(context,"⌄",18,Daybook.accent(context),false);head.addView(arrow);content.addView(head);
            LinearLayout timeline=Ui.column(context);timeline.setPadding(0,Ui.dp(context,10),0,0);boolean open=openedDays.contains(day.key)||(day.key.equals(today)&&!closedDays.contains(day.key));timeline.setVisibility(open?View.VISIBLE:View.GONE);arrow.setText(open?"⌃":"⌄");content.addView(timeline);boolean[] loaded={false};
            Runnable populate=()->{if(!loaded[0]){loaded[0]=true;if(day.legacy){timeline.addView(Daybook.text(context,"Imported without a copy date. These expire 30 days after this update.",11,Daybook.muted(context),false));Ui.space(timeline,8);}appendPage(context,timeline,day,0,0,0,refresh);}};
            if(open)populate.run();
            head.setContentDescription((open?"Hide":"Show")+" clipboard timeline for "+day.key);
            head.setOnClickListener(v->{Daybook.feedback(v);boolean show=timeline.getVisibility()!=View.VISIBLE;if(show){populate.run();openedDays.add(day.key);closedDays.remove(day.key);timeline.setVisibility(View.VISIBLE);Daybook.motion(timeline);}else{openedDays.remove(day.key);closedDays.add(day.key);timeline.setVisibility(View.GONE);}arrow.setText(show?"⌃":"⌄");head.setContentDescription((show?"Hide":"Show")+" clipboard timeline for "+day.key);});
            host.addView(section);
        }
        long expiry=ClipList.nextVisibleExpiry(context);if(expiry>0){expiryHost=host;expiryRefresh=()->{ClipboardExpiryJob.schedule(context);refresh.run();};host.postDelayed(expiryRefresh,Math.max(1,expiry-System.currentTimeMillis()+1));}
    }
    private void appendPage(Context context,LinearLayout timeline,ClipboardArchive.Day day,long beforeTime,long beforeId,int already,Runnable refresh){
        List<ClipboardArchive.Item> entries=ClipList.dayPage(context,day,beforeTime,beforeId,PAGE);
        for(ClipboardArchive.Item entry:entries)addEntry(context,timeline,entry,refresh);
        if(!entries.isEmpty()&&entries.size()==PAGE&&already+entries.size()<day.count){ClipboardArchive.Item last=entries.get(entries.size()-1);Button more=Daybook.button(context,"Show more copies",false,v->{timeline.removeView(v);appendPage(context,timeline,day,last.copiedAt,last.id,already+entries.size(),refresh);});more.setContentDescription("Show more clipboard copies for "+day.key);timeline.addView(more,new LinearLayout.LayoutParams(-1,Ui.dp(context,44)));}
    }
    private void addEntry(Context context,LinearLayout timeline,ClipboardArchive.Item entry,Runnable refresh){
        View separator=new View(context);separator.setBackgroundColor(Daybook.line(context));timeline.addView(separator,new LinearLayout.LayoutParams(-1,Ui.dp(context,.5f)));
        LinearLayout item=Ui.column(context),head=Ui.row(context);head.setMinimumHeight(Ui.dp(context,51));head.setPadding(0,Ui.dp(context,7),0,Ui.dp(context,7));head.setBackground(Daybook.ripple(context,android.graphics.Color.TRANSPARENT,7));TextView time=Daybook.text(context,entry.legacy?"Earlier":DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(entry.copiedAt)),11,Daybook.muted(context),false);head.addView(time,new LinearLayout.LayoutParams(Ui.dp(context,67),-2));TextView preview=Daybook.text(context,entry.preview,16,Daybook.ink(context),false);preview.setMaxLines(2);preview.setEllipsize(android.text.TextUtils.TruncateAt.END);head.addView(preview,new LinearLayout.LayoutParams(0,-2,1));item.addView(head);
        LinearLayout detail=Ui.column(context);detail.setPadding(Ui.dp(context,67),0,0,Ui.dp(context,11));detail.setVisibility(View.GONE);item.addView(detail);
        Runnable expand=()->{
            String text=ClipList.text(context,entry.id);detail.removeAllViews();
            if(text==null){detail.addView(Daybook.text(context,ClipList.error().isEmpty()?"This copy has expired or was deleted.":ClipList.error(),13,Daybook.muted(context),false));return;}
            TextView full=Daybook.text(context,text,17,Daybook.ink(context),false);full.setTextIsSelectable(true);full.setContentDescription("Full clipboard copy "+entry.id);detail.addView(full);Ui.space(detail,8);
            if(!entry.legacy){detail.addView(Daybook.text(context,"Copied "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(entry.copiedAt)),10,Daybook.muted(context),false));Ui.space(detail,3);}
            detail.addView(Daybook.text(context,"Expires "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(entry.expiresAt)),10,Daybook.muted(context),false));Ui.space(detail,8);
            LinearLayout actions=Ui.row(context);Button copy=Daybook.button(context,"Copy",false,v->{String current=ClipList.text(context,entry.id);if(current==null){refresh.run();return;}Ui.copy(context,current);});copy.setContentDescription("Copy clipboard "+entry.id);Button delete=Daybook.button(context,"Delete",false,v->{if(ClipList.remove(context,entry.id)){openedEntries.remove(entry.id);refresh.run();}else android.widget.Toast.makeText(context,ClipList.error().isEmpty()?"This copy has already expired.":ClipList.error(),android.widget.Toast.LENGTH_SHORT).show();});delete.setContentDescription("Delete clipboard "+entry.id);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,Ui.dp(context,44),1);cp.rightMargin=Ui.dp(context,8);actions.addView(copy,cp);actions.addView(delete,new LinearLayout.LayoutParams(0,Ui.dp(context,44),1));detail.addView(actions);
        };
        if(openedEntries.contains(entry.id)){expand.run();detail.setVisibility(View.VISIBLE);}
        head.setContentDescription((detail.getVisibility()==View.VISIBLE?"Close":"Open")+" clipboard "+entry.id);
        head.setOnClickListener(v->{Daybook.feedback(v);boolean show=detail.getVisibility()!=View.VISIBLE;if(show){expand.run();openedEntries.add(entry.id);detail.setVisibility(View.VISIBLE);Daybook.motion(detail);}else{openedEntries.remove(entry.id);detail.setVisibility(View.GONE);}head.setContentDescription((show?"Close":"Open")+" clipboard "+entry.id);});timeline.addView(item);
    }
}
