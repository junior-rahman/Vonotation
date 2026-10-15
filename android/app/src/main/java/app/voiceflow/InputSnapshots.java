package app.voiceflow;

import android.os.Build;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

final class InputSnapshots {
    // Called from the API 31 guarded read path or the Android 13 accessibility editor.
    @android.annotation.TargetApi(31)
    static EditorInsertion.Snapshot from(SurroundingText value) {
        if(value==null || value.getText()==null)return null;
        String text=value.getText().toString();int a=value.getSelectionStart(),b=value.getSelectionEnd();
        if(a<0 || b<0 || a>text.length() || b>text.length())return null;
        int lo=Math.min(a,b),hi=Math.max(a,b),offset=value.getOffset();
        return new EditorInsertion.Snapshot(text.substring(0,lo),text.substring(lo,hi),text.substring(hi),offset<0?-1:offset+a,offset<0?-1:offset+b);
    }
    static EditorInsertion.Snapshot read(InputConnection connection,int selectionStart,int selectionEnd) {
        if(connection==null)return null;
        if(Build.VERSION.SDK_INT>=31) {
            EditorInsertion.Snapshot result=from(connection.getSurroundingText(EditorInsertion.CONTEXT,EditorInsertion.CONTEXT,0));
            if(result!=null)return result;
        }
        ExtractedTextRequest request=new ExtractedTextRequest();request.hintMaxChars=4096;
        ExtractedText extracted=connection.getExtractedText(request,0);
        if(extracted!=null && extracted.text!=null && extracted.selectionStart>=0 && extracted.selectionEnd>=0) {
            int a=extracted.selectionStart,b=extracted.selectionEnd;String text=extracted.text.toString();int lo=Math.min(a,b),hi=Math.max(a,b);
            if(hi<=text.length())return new EditorInsertion.Snapshot(text.substring(Math.max(0,lo-EditorInsertion.CONTEXT),lo),text.substring(lo,hi),text.substring(hi,Math.min(text.length(),hi+EditorInsertion.CONTEXT)),extracted.startOffset+a,extracted.startOffset+b);
        }
        CharSequence before=connection.getTextBeforeCursor(EditorInsertion.CONTEXT,0),after=connection.getTextAfterCursor(EditorInsertion.CONTEXT,0),selected=connection.getSelectedText(0);
        if(before==null || after==null)return null;
        return new EditorInsertion.Snapshot(before.toString(),selected==null?"":selected.toString(),after.toString(),selectionStart,selectionEnd);
    }
}
