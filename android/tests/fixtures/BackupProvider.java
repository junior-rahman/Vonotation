package app.voiceflow.fixture;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;

/** A real document provider for isolated backup QA; it never contacts a cloud account. */
public final class BackupProvider extends DocumentsProvider {
    public static final String AUTHORITY="app.voiceflow.fixture.backups";
    public static final String DOCUMENT="voiceflow-backup";
    static volatile boolean failWrites;
    static volatile boolean unavailable;
    static volatile java.util.concurrent.CountDownLatch readBlock,writeBlock;
    static volatile boolean readEntered,writeEntered;
    static File archive;
    public boolean onCreate(){archive=new File(getContext().getFilesDir(),"fixture.vfbackup");try{if(!archive.exists())archive.createNewFile();return true;}catch(Exception e){return false;}}
    public Cursor queryRoots(String[] projection){String[] columns=projection==null?new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.COLUMN_MIME_TYPES}:projection;MatrixCursor cursor=new MatrixCursor(columns);MatrixCursor.RowBuilder row=cursor.newRow();for(String column:columns){if(column.equals(DocumentsContract.Root.COLUMN_ROOT_ID))row.add("fixture");else if(column.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID))row.add(DOCUMENT);else if(column.equals(DocumentsContract.Root.COLUMN_TITLE))row.add("Backup QA provider");else if(column.equals(DocumentsContract.Root.COLUMN_FLAGS))row.add(0);else if(column.equals(DocumentsContract.Root.COLUMN_MIME_TYPES))row.add("application/octet-stream");else row.add(null);}return cursor;}
    public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{check(id);String[] columns=projection==null?new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_LAST_MODIFIED}:projection;MatrixCursor cursor=new MatrixCursor(columns);MatrixCursor.RowBuilder row=cursor.newRow();for(String column:columns){if(column.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID))row.add(DOCUMENT);else if(column.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))row.add("VoiceFlow QA.vfbackup");else if(column.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))row.add("application/octet-stream");else if(column.equals(DocumentsContract.Document.COLUMN_SIZE))row.add(archive.length());else if(column.equals(DocumentsContract.Document.COLUMN_FLAGS))row.add(DocumentsContract.Document.FLAG_SUPPORTS_WRITE);else if(column.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED))row.add(archive.lastModified());else if(column.equals("fixture_read_entered"))row.add(readEntered?1:0);else if(column.equals("fixture_write_entered"))row.add(writeEntered?1:0);else row.add(null);}return cursor;}
    public Cursor queryChildDocuments(String parent,String[] projection,String sort)throws FileNotFoundException{return queryDocument(parent,projection);}
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{check(id);boolean writing=mode.contains("w");if(writing && failWrites)throw new FileNotFoundException("Fixture provider refused the write");ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(archive,ParcelFileDescriptor.parseMode(mode));java.util.concurrent.CountDownLatch wait=writing?writeBlock:readBlock;if(wait!=null){if(writing){writeBlock=null;writeEntered=true;}else{readBlock=null;readEntered=true;}boolean released=false;try{released=wait.await(90,java.util.concurrent.TimeUnit.SECONDS);if(!released)throw new FileNotFoundException("Fixture provider latch timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new FileNotFoundException("Fixture provider latch interrupted");}finally{if(!released)try{descriptor.close();}catch(Exception ignored){}}}return descriptor;}
    private static void check(String id)throws FileNotFoundException{if(!DOCUMENT.equals(id) || unavailable)throw new FileNotFoundException("Fixture document unavailable");}
    static volatile java.util.concurrent.CountDownLatch currentBlock;
    static void block(boolean writing){currentBlock=new java.util.concurrent.CountDownLatch(1);if(writing){writeEntered=false;writeBlock=currentBlock;}else{readEntered=false;readBlock=currentBlock;}}
    static void unblock(){java.util.concurrent.CountDownLatch block=currentBlock;if(block!=null)block.countDown();currentBlock=null;readBlock=null;writeBlock=null;}
    static void reset()throws Exception{unblock();failWrites=false;unavailable=false;try(FileOutputStream out=new FileOutputStream(archive)){}}
    static void tamper()throws Exception{byte[] bytes=read();if(bytes.length==0)throw new Exception("No archive to tamper");bytes[bytes.length-1]^=1;try(FileOutputStream out=new FileOutputStream(archive)){out.write(bytes);}}
    static byte[] read()throws Exception{try(FileInputStream in=new FileInputStream(archive);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);return out.toByteArray();}}
}
