package app.voiceflow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only, explicitly granted copies. Never exposes the private recordings directory. */
public final class RecordingShare extends ContentProvider {
    private static final long LIFETIME=24*60*60*1000L;
    static Uri prepare(Context context,List<File> files)throws IOException{
        if(files.isEmpty())throw new IOException("The source audio is unavailable.");
        File root=new File(context.getCacheDir(),"recording-shares");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Not enough storage to share audio.");
        File[] old=root.listFiles();if(old!=null)for(File folder:old)if(System.currentTimeMillis()-folder.lastModified()>LIFETIME){File[] contents=folder.listFiles();if(contents!=null)for(File f:contents)f.delete();folder.delete();}
        File folder=new File(root,UUID.randomUUID().toString());if(!folder.mkdir())throw new IOException("Could not prepare the share.");
        File output=new File(folder,files.size()==1?"Recording.m4a":"Recording-parts.zip");boolean complete=false;
        try{
            if(files.size()==1){try(OutputStream out=new FileOutputStream(output)){copy(files.get(0),out);}}
            else try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(output))){
                zip.putNextEntry(new ZipEntry("Read-me.txt"));zip.write("Source audio parts in chronological order. Play the numbered M4A files in sequence. No source audio was re-encoded.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
                int i=0;for(File file:files){zip.putNextEntry(new ZipEntry(String.format(java.util.Locale.ROOT,"%04d.m4a",++i)));copy(file,zip);zip.closeEntry();}
            }
            complete=true;return new Uri.Builder().scheme("content").authority(context.getPackageName()+".recording-share").appendPath(folder.getName()).appendPath(output.getName()).build();
        }finally{if(!complete){output.delete();folder.delete();}}
    }
    private static void copy(File file,OutputStream out)throws IOException{
        if(!file.isFile()||file.length()==0)throw new IOException("A source segment is missing. No incomplete copy was shared.");
        long expected=file.length(),count=0;try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);count+=n;}}
        if(count!=expected)throw new IOException("Source audio changed. Try sharing again.");
    }
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException{
        try{
            List<String> parts=uri.getPathSegments();
            if(parts.size()!=2||!parts.get(0).matches("[a-f0-9-]{36}")||!(parts.get(1).equals("Recording.m4a")||parts.get(1).equals("Recording-parts.zip")))throw new IOException();
            File root=new File(getContext().getCacheDir(),"recording-shares").getCanonicalFile();
            File value=new File(new File(root,parts.get(0)),parts.get(1)).getCanonicalFile();
            if(!root.equals(value.getParentFile().getParentFile())||!value.isFile()||System.currentTimeMillis()-value.lastModified()>LIFETIME)throw new IOException();return value;
        }catch(IOException error){throw new FileNotFoundException("This shared copy is unavailable. Share the audio again from the app.");}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public String getType(Uri uri){return "Recording-parts.zip".equals(uri.getLastPathSegment())?"application/zip":"audio/mp4";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);
        try{File source=file(uri);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=source.getName();else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=source.length();}cursor.addRow(values);}catch(FileNotFoundException ignored){}return cursor;
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
}
