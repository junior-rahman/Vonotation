package app.voiceflow;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Private, local-only clipboard journal. Display limits never evict unexpired copies. */
final class ClipboardArchive extends SQLiteOpenHelper {
    static final String DATABASE="clipboard-history.db";
    private static final int CHUNK=32768;
    private static ClipboardArchive instance;
    static synchronized ClipboardArchive get(Context context){
        if(instance==null)instance=new ClipboardArchive(context.getApplicationContext(),DATABASE);
        return instance;
    }
    // Named databases let the isolated native fixture avoid the user's real clipboard journal.
    ClipboardArchive(Context context,String name){super(context,name,null,2);}
    @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE copies (_id INTEGER PRIMARY KEY AUTOINCREMENT, copied_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, preview TEXT NOT NULL, legacy INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX copies_order ON copies(copied_at DESC,_id DESC)");
        db.execSQL("CREATE INDEX copies_expiry ON copies(expires_at)");
        // Small chunks also support full copies larger than Android's CursorWindow row limit.
        db.execSQL("CREATE TABLE copy_chunks (copy_id INTEGER NOT NULL REFERENCES copies(_id) ON DELETE CASCADE, part INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(copy_id,part))");
        db.execSQL("CREATE TABLE clipboard_meta (name TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion==1&&newVersion==2){db.execSQL("ALTER TABLE copies ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0");return;}throw new IllegalStateException("Unsupported clipboard archive version");}
    static final class Item {
        final long id,copiedAt,expiresAt;final String preview;final boolean legacy;
        Item(long id,long copiedAt,long expiresAt,String preview,boolean legacy){this.id=id;this.copiedAt=copiedAt;this.expiresAt=expiresAt;this.preview=preview;this.legacy=legacy;}
    }
    static final class Day {
        final String key;final long time;final boolean legacy;int count;
        long rangeStart,rangeEnd;
        Day(String key,long time,boolean legacy){this.key=key;this.time=time;this.legacy=legacy;}
    }
    synchronized List<Day> days(java.util.TimeZone zone,long now){
        SQLiteDatabase db=getReadableDatabase();ArrayList<Day> days=new ArrayList<>();
        java.util.Calendar boundary=java.util.Calendar.getInstance(zone);boundary.setTimeInMillis(now);boundary.set(java.util.Calendar.HOUR_OF_DAY,0);boundary.set(java.util.Calendar.MINUTE,0);boundary.set(java.util.Calendar.SECOND,0);boundary.set(java.util.Calendar.MILLISECOND,0);boundary.add(java.util.Calendar.DAY_OF_MONTH,1);
        // At most 32 calendar ranges plus legacy. Never transfer every row to build collapsed headers.
        long oldest=now-ClipboardRetention.RETENTION_MILLIS,normalEnd=boundary.getTimeInMillis(),normalStart=normalEnd;
        for(int i=0;i<32;i++){long upper=boundary.getTimeInMillis();boundary.add(java.util.Calendar.DAY_OF_MONTH,-1);long lower=boundary.getTimeInMillis();
            normalStart=lower;
            try(Cursor cursor=db.rawQuery("SELECT MAX(copied_at),COUNT(*) FROM copies WHERE deleted_at=0 AND legacy=0 AND copied_at>=? AND copied_at<? AND expires_at>?",new String[]{Long.toString(lower),Long.toString(upper),Long.toString(now)})){
                if(cursor.moveToFirst()&&cursor.getLong(1)>0){long time=cursor.getLong(0);Day day=new Day(ClipboardRetention.key(time,zone),time,false);day.count=cursor.getInt(1);days.add(day);}
            }
            if(lower<=oldest)break;
        }
        // A clock rollback must not hide copies that still have a valid fixed expiry.
        try(Cursor cursor=db.rawQuery("SELECT MAX(copied_at),COUNT(*) FROM copies WHERE deleted_at=0 AND legacy=0 AND (copied_at<? OR copied_at>=?) AND expires_at>?",new String[]{Long.toString(normalStart),Long.toString(normalEnd),Long.toString(now)})){
            if(cursor.moveToFirst()&&cursor.getLong(1)>0){Day day=new Day("other_dates",cursor.getLong(0),false);day.count=cursor.getInt(1);day.rangeStart=normalStart;day.rangeEnd=normalEnd;days.add(0,day);}
        }
        try(Cursor cursor=db.rawQuery("SELECT COUNT(*) FROM copies WHERE deleted_at=0 AND legacy=1 AND expires_at>?",new String[]{Long.toString(now)})){if(cursor.moveToFirst()&&cursor.getLong(0)>0){Day day=new Day("legacy",0,true);day.count=cursor.getInt(0);days.add(day);}}
        return days;
    }
    synchronized List<Item> dayPage(Day day,java.util.TimeZone zone,long beforeTime,long beforeId,int limit,long now){
        SQLiteDatabase db=getWritableDatabase();String where="deleted_at=0 AND expires_at>?";ArrayList<String> args=new ArrayList<>();args.add(Long.toString(now));
        if(day.legacy)where+=" AND legacy=1";
        else if("other_dates".equals(day.key)){where+=" AND legacy=0 AND (copied_at<? OR copied_at>=?)";args.add(Long.toString(day.rangeStart));args.add(Long.toString(day.rangeEnd));}
        else{java.util.Calendar start=java.util.Calendar.getInstance(zone);start.setTimeInMillis(day.time);start.set(java.util.Calendar.HOUR_OF_DAY,0);start.set(java.util.Calendar.MINUTE,0);start.set(java.util.Calendar.SECOND,0);start.set(java.util.Calendar.MILLISECOND,0);long lower=start.getTimeInMillis();start.add(java.util.Calendar.DAY_OF_MONTH,1);where+=" AND legacy=0 AND copied_at>=? AND copied_at<?";args.add(Long.toString(lower));args.add(Long.toString(start.getTimeInMillis()));}
        if(beforeId>0){where+=" AND (copied_at<? OR (copied_at=? AND _id<?))";args.add(Long.toString(beforeTime));args.add(Long.toString(beforeTime));args.add(Long.toString(beforeId));}
        ArrayList<Item> items=new ArrayList<>();
        try(Cursor cursor=db.query("copies",new String[]{"_id","copied_at","expires_at","preview","legacy"},where,args.toArray(new String[0]),null,null,"copied_at DESC,_id DESC",Integer.toString(Math.max(1,limit)))){while(cursor.moveToNext())items.add(new Item(cursor.getLong(0),cursor.getLong(1),cursor.getLong(2),cursor.getString(3),cursor.getInt(4)!=0));}
        return items;
    }
    /** Unknown legacy dates stay unknown. Their first migration starts a clearly labelled grace period. */
    synchronized void migrateLegacy(JSONArray old,long now){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(!"1".equals(meta(db,"legacy_migrated"))){
                // The old list is newest first; reverse inserts preserve that order for equal timestamps.
                for(int i=old.length()-1;i>=0;i--){String text=old.optString(i,"");if(ClipboardText.storable(text))insert(db,text,0,expires(now),true);}
                putMeta(db,"legacy_migrated","1");
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized long remember(String text,long now){
        if(!ClipboardText.storable(text))return -1;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            long id=insert(db,text,now,expires(now),false);
            putMeta(db,"own_hash",fingerprint(text));putMeta(db,"own_time",Long.toString(now));
            db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    /** Only event hashes/timestamps are retained for known credential copies, never their text. */
    synchronized void exclude(String event){SQLiteDatabase db=getWritableDatabase();if(event==null)putMeta(db,"blocked_unknown","1");else{putMeta(db,"blocked_event",event);putMeta(db,"blocked_unknown","0");}}
    synchronized boolean excluded(String event){
        SQLiteDatabase db=getWritableDatabase();
        if("1".equals(meta(db,"blocked_unknown"))){db.beginTransaction();try{putMeta(db,"blocked_event",event);putMeta(db,"blocked_unknown","0");db.setTransactionSuccessful();}finally{db.endTransaction();}return true;}
        return event.equals(meta(db,"blocked_event"));
    }
    /** Re-reading the same platform clip does not create a copy or extend its lifetime. */
    synchronized boolean capture(String text,long copiedAt,String event,boolean ownLabel,long now){
        if(!ClipboardText.storable(text))return false;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(event.equals(meta(db,"last_event"))){db.setTransactionSuccessful();return false;}
            putMeta(db,"last_event",event);
            long ownTime=number(meta(db,"own_time"));
            boolean explicitCopy=ownLabel && now>=ownTime && now-ownTime<=10000 && fingerprint(text).equals(meta(db,"own_hash"));
            if(explicitCopy){putMeta(db,"own_hash","");putMeta(db,"own_time","0");}
            long timestamp=copiedAt>0?Math.min(copiedAt,now):now;
            boolean add=!explicitCopy&&!ClipboardRetention.expired(timestamp,now);
            if(add)insert(db,text,timestamp,expires(timestamp),false);
            db.setTransactionSuccessful();return add;
        }finally{db.endTransaction();}
    }
    synchronized List<Item> page(long beforeTime,long beforeId,int limit,long now){
        SQLiteDatabase db=getWritableDatabase();
        String where="deleted_at=0 AND expires_at>?";ArrayList<String> args=new ArrayList<>();args.add(Long.toString(now));
        if(beforeId>0){where+=" AND (copied_at<? OR (copied_at=? AND _id<?))";args.add(Long.toString(beforeTime));args.add(Long.toString(beforeTime));args.add(Long.toString(beforeId));}
        ArrayList<Item> items=new ArrayList<>();
        try(Cursor cursor=db.query("copies",new String[]{"_id","copied_at","expires_at","preview","legacy"},where,args.toArray(new String[0]),null,null,"copied_at DESC,_id DESC",Integer.toString(Math.max(1,limit)))){
            while(cursor.moveToNext())items.add(new Item(cursor.getLong(0),cursor.getLong(1),cursor.getLong(2),cursor.getString(3),cursor.getInt(4)!=0));
        }
        return items;
    }
    synchronized String text(long id,long now){
        SQLiteDatabase db=getWritableDatabase();
        try(Cursor row=db.rawQuery("SELECT 1 FROM copies WHERE deleted_at=0 AND _id=? AND expires_at>?",new String[]{Long.toString(id),Long.toString(now)})){if(!row.moveToFirst())return null;}
        StringBuilder text=new StringBuilder();
        try(Cursor cursor=db.query("copy_chunks",new String[]{"text"},"copy_id=?",new String[]{Long.toString(id)},null,null,"part ASC")){while(cursor.moveToNext())text.append(cursor.getString(0));}
        return text.toString();
    }
    synchronized boolean remove(long id){ContentValues value=new ContentValues();value.put("deleted_at",System.currentTimeMillis());return getWritableDatabase().update("copies",value,"_id=? AND deleted_at=0",new String[]{Long.toString(id)})>0;}
    synchronized boolean undo(long id,long now){ContentValues value=new ContentValues();value.put("deleted_at",0);return getWritableDatabase().update("copies",value,"_id=? AND deleted_at>0 AND deleted_at>? AND expires_at>?",new String[]{Long.toString(id),Long.toString(now-DeletionUndo.KEEP),Long.toString(now)})>0;}
    synchronized List<Item> deleted(long now){ArrayList<Item> result=new ArrayList<>();try(Cursor cursor=getReadableDatabase().query("copies",new String[]{"_id","copied_at","expires_at","preview","legacy"},"deleted_at>0 AND deleted_at>? AND expires_at>?",new String[]{Long.toString(now-DeletionUndo.KEEP),Long.toString(now)},null,null,"deleted_at DESC")){while(cursor.moveToNext())result.add(new Item(cursor.getLong(0),cursor.getLong(1),cursor.getLong(2),cursor.getString(3),cursor.getInt(4)!=0));}return result;}
    synchronized int prune(long now){return prune(getWritableDatabase(),now);}
    synchronized long nextExpiry(){try(Cursor cursor=getReadableDatabase().rawQuery("SELECT MIN(CASE WHEN deleted_at>0 THEN MIN(expires_at,deleted_at+86400000) ELSE expires_at END) FROM copies",null)){return cursor.moveToFirst()&&!cursor.isNull(0)?cursor.getLong(0):0;}}
    synchronized long nextVisibleExpiry(long now){try(Cursor cursor=getReadableDatabase().rawQuery("SELECT MIN(expires_at) FROM copies WHERE deleted_at=0 AND expires_at>?",new String[]{Long.toString(now)})){return cursor.moveToFirst()&&!cursor.isNull(0)?cursor.getLong(0):0;}}
    synchronized long count(long now){try(Cursor cursor=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM copies WHERE deleted_at=0 AND expires_at>?",new String[]{Long.toString(now)})){return cursor.moveToFirst()?cursor.getLong(0):0;}}
    private static int prune(SQLiteDatabase db,long now){return db.delete("copies","expires_at<=? OR (deleted_at>0 AND deleted_at<=?)",new String[]{Long.toString(now),Long.toString(now-DeletionUndo.KEEP)});}
    private static long expires(long timestamp){return timestamp>Long.MAX_VALUE-ClipboardRetention.RETENTION_MILLIS?Long.MAX_VALUE:timestamp+ClipboardRetention.RETENTION_MILLIS;}
    private static long insert(SQLiteDatabase db,String text,long timestamp,long expiry,boolean legacy){
        ContentValues entry=new ContentValues();entry.put("copied_at",timestamp);entry.put("expires_at",expiry);entry.put("preview",ClipboardText.preview(text));entry.put("legacy",legacy?1:0);
        long id=db.insertOrThrow("copies",null,entry);int part=0;
        for(int start=0;start<text.length();){int end=Math.min(text.length(),start+CHUNK);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;ContentValues chunk=new ContentValues();chunk.put("copy_id",id);chunk.put("part",part++);chunk.put("text",text.substring(start,end));db.insertOrThrow("copy_chunks",null,chunk);start=end;}
        return id;
    }
    private static String meta(SQLiteDatabase db,String name){try(Cursor cursor=db.query("clipboard_meta",new String[]{"value"},"name=?",new String[]{name},null,null,null)){return cursor.moveToFirst()?cursor.getString(0):"";}}
    private static void putMeta(SQLiteDatabase db,String name,String value){ContentValues data=new ContentValues();data.put("name",name);data.put("value",value);db.insertWithOnConflict("clipboard_meta",null,data,SQLiteDatabase.CONFLICT_REPLACE);}
    private static long number(String value){try{return Long.parseLong(value);}catch(NumberFormatException ignored){return 0;}}
    static String fingerprint(String text){
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
