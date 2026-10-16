package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.PersistableBundle;
import java.util.List;
import java.util.TimeZone;
import org.json.JSONArray;

/** Isolated SQLite checks: no real clipboard, provider, recording or user archive is touched. */
public final class ClipboardStorageTest extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context context=getTargetContext();String name="clipboard-fixture-"+System.nanoTime()+".db";ClipboardArchive archive=new ClipboardArchive(context,name);
        try{
            long now=System.currentTimeMillis(),ttl=ClipboardRetention.RETENTION_MILLIS;
            String exact="  first\nsecond\t😀 বাংলা  ";long first=archive.remember(exact,now);
            require(exact.equals(archive.text(first,now)),"Whitespace or Unicode changed in storage.");
            require(!archive.capture(exact,now+1,"own-event",true,now+1)&&archive.count(now+1)==1,"Own platform callback duplicated an explicit copy.");
            require(archive.capture("platform text",now+2,"event-2",false,now+2),"Platform copy was not saved.");
            require(!archive.capture("platform text",now+2,"event-2",false,now+10000),"Opening or re-reading renewed a clip.");
            archive.exclude("secret-event");require(archive.excluded("secret-event")&&!archive.excluded("public-event"),"Known private copy became eligible after editor focus changed.");
            archive.exclude(null);require(archive.excluded("later-readable-secret")&&archive.excluded("later-readable-secret")&&!archive.excluded("new-public-copy"),"Unread credential event exclusion did not survive later availability.");
            long old=archive.remember("expires exactly",now-ttl+1);
            require(archive.text(old,now)!=null&&archive.text(old,now+1)==null,"Expiry is not exact at 30 elapsed days.");
            long count=archive.count(now+2);require(!archive.capture("too old",now-ttl,"expired-event",false,now)&&archive.count(now+2)==count,"An already expired platform copy was revived.");
            for(int i=0;i<125;i++)archive.remember("synthetic copy "+i,now+i+10);
            require(archive.count(now+200)==127,"Count-based eviction removed an unexpired copy.");
            List<ClipboardArchive.Item> firstPage=archive.page(0,0,50,now+200);require(firstPage.size()==50,"First archive page is not bounded.");ClipboardArchive.Item last=firstPage.get(49);List<ClipboardArchive.Item> secondPage=archive.page(last.copiedAt,last.id,50,now+200);require(secondPage.size()==50&&secondPage.get(0).id!=firstPage.get(0).id,"Keyset pagination lost or duplicated a page.");
            StringBuilder huge=new StringBuilder(" \n");for(int i=0;i<1100000;i++)huge.append(i%13==0?"😀":"x");huge.append("\n  ");String payload=huge.toString();long big=archive.remember(payload,now+300);
            require(payload.equals(archive.text(big,now+301)),"Large text was truncated or exceeded a CursorWindow row.");
            require(archive.remove(big)&&archive.text(big,now+301)==null,"Manual deletion left a readable entry.");
            try(Cursor chunks=archive.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM copy_chunks WHERE copy_id=?",new String[]{Long.toString(big)})){require(chunks.moveToFirst()&&chunks.getInt(0)==0,"Deletion orphaned full-text chunks.");}
            long beforeLegacy=archive.count(now+400);archive.migrateLegacy(new JSONArray().put("legacy newest").put("legacy oldest"),now+400);archive.migrateLegacy(new JSONArray().put("legacy newest"),now+500);
            require(archive.count(now+500)==beforeLegacy+2,"Migration duplicated or lost the old snapshot.");
            List<ClipboardArchive.Day> days=archive.days(TimeZone.getDefault(),now+500);ClipboardArchive.Day legacy=null;for(ClipboardArchive.Day day:days)if(day.legacy)legacy=day;require(legacy!=null&&legacy.count==2&&legacy.time==0,"Migration invented a known copy date.");
            List<ClipboardArchive.Item> imported=archive.dayPage(legacy,TimeZone.getDefault(),0,0,50,now+500);require("legacy newest".equals(archive.text(imported.get(0).id,now+500)),"Legacy newest-first order changed.");
            require(archive.nextExpiry()==now+1&&archive.nextVisibleExpiry(now+500)==now+ttl,"Visible expiry includes an expired row and can create a refresh loop.");
            require(archive.prune(now+500)==1,"Logical reads deleted rows before the cleanup worker could observe them.");
            long earliestExpiry=archive.nextExpiry();require(earliestExpiry==now+ttl,"Re-reading reset the earliest lifetime.");
            require(archive.prune(now+ttl)>0&&archive.text(first,now+ttl)==null,"Background cleanup did not remove expired records.");
            require(archive.count(now+ttl)>0,"Cleanup removed newer unexpired copies.");
            List<ClipboardArchive.Day> rollback=archive.days(TimeZone.getDefault(),now-2*24*60*60*1000L);ClipboardArchive.Day other=null;for(ClipboardArchive.Day day:rollback)if(day.key.equals("other_dates"))other=day;require(other!=null&&other.count>0&&!archive.dayPage(other,TimeZone.getDefault(),0,0,50,now-2*24*60*60*1000L).isEmpty(),"Clock rollback made unexpired copies unreachable.");
            ClipData sensitive=ClipData.newPlainText("private","not archived");PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);sensitive.getDescription().setExtras(extras);require(ClipList.sensitive(sensitive),"Sensitive system clipboard flag was ignored.");
            require(!ClipList.sensitive(ClipData.newPlainText("ordinary","allowed")),"Ordinary copy was incorrectly private.");
            archive.close();archive=new ClipboardArchive(context,name);require(archive.count(now+ttl)>0,"Archive did not survive database reopen.");
            result.putString("result","PASS: "+checks+" SQLite full-text, large chunk, 30-day expiry, no count eviction, stable event TTL, own-copy dedup, pagination, unknown-date migration, sensitive flag, source cleanup and reopen checks.");
        }catch(Throwable error){result.putString("failure",error.toString());}
        finally{archive.close();context.deleteDatabase(name);}
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    private void require(boolean okay,String message)throws Exception{checks++;if(!okay)throw new Exception(message);}
}
