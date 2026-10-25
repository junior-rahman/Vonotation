package app.voiceflow;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.lang.reflect.Method;
import org.json.*;

/** Production metadata mutations and share copies with disposable synthetic sources. */
final class VocadingChecks {
    static int run(Context context)throws Exception{
        String before=Store.get(context,"notes","[]");File a=new File(Notes.sourceDirectory(context),"vocading-check-0.m4a"),b=new File(Notes.sourceDirectory(context),"vocading-check-1.m4a");
        try{
            try(FileOutputStream out=new FileOutputStream(a)){out.write(new byte[]{1,2,3});}
            try(FileOutputStream out=new FileOutputStream(b)){out.write(new byte[]{4,5,6});}
            JSONObject original=new JSONObject().put("id",9001).put("created",9001).put("revision",1).put("text","Original words").put("raw","Original words").put("audio",a.getName()).put("parts",new JSONArray().put(a.getName()).put(b.getName())).put("pending",true);
            require(Store.prefs(context).edit().putString("notes",new JSONArray().put(original).toString()).commit());
            require(Notes.editSaved(context,9001,"My title","Edited বাংলা words"));
            JSONObject edited=read(context);require("Original words".equals(edited.getString("raw"))&&edited.getBoolean("user_edited")&&!edited.getBoolean("pending"));
            Method update=Notes.class.getDeclaredMethod("updateCaptured",Context.class,JSONObject.class,Notes.NoteMutation.class);update.setAccessible(true);
            require(!(Boolean)update.invoke(null,context,original,(Notes.NoteMutation)item->item.put("text","late provider overwrite")));
            require(Notes.togglePin(context,9001)&&read(context).getBoolean("pinned"));
            require(Notes.trashSaved(context,9001)&&read(context).getBoolean("trashed")&&a.exists()&&b.exists());
            require(!Notes.editSaved(context,9001,"Late edit","Late edit"));
            require(!Notes.keepCallFiles(context,9001,java.util.Arrays.asList(a,b),true));
            require(Notes.restoreSaved(context,9001)&&!read(context).optBoolean("trashed"));
            require("Edited বাংলা words".equals(read(context).getString("text"))&&a.exists()&&b.exists());
            Uri shared=RecordingShare.prepare(context,java.util.Arrays.asList(a,b));
            try(InputStream input=context.getContentResolver().openInputStream(shared);java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(input)){
                require(zip.getNextEntry().getName().equals("Read-me.txt"));zip.closeEntry();require(zip.getNextEntry().getName().equals("0001.m4a"));require(zip.read()==1);zip.closeEntry();require(zip.getNextEntry().getName().equals("0002.m4a"));require(zip.read()==4);
            }
            boolean denied=false;try{context.getContentResolver().openFileDescriptor(shared,"w");}catch(FileNotFoundException expected){denied=true;}require(denied);
            boolean absent=false;try{RecordingShare.prepare(context,java.util.Arrays.asList(a,new File(a.getParentFile(),"missing.m4a")));}catch(IOException expected){absent=true;}require(absent);
            return 16;
        }finally{Store.prefs(context).edit().putString("notes",before).commit();a.delete();b.delete();}
    }
    private static JSONObject read(Context context)throws Exception{return new JSONArray(Store.get(context,"notes","[]")).getJSONObject(0);}
    private static void require(boolean ok){if(!ok)throw new AssertionError("Vocading source preservation, edit, trash or share check failed");}
}
