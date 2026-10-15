package app.voiceflow;

import java.util.ArrayList;

/** Android-free clipboard rules. Preview formatting never changes the stored/pasted value. */
final class ClipboardText {
    static final int MAX_ITEMS=20,MAX_CHARS=4000;
    private ClipboardText() {}
    static boolean usable(String value){
        if(value==null || value.length()==0)return false;
        for(int i=0;i<value.length();){int cp=value.codePointAt(i);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))return true;i+=Character.charCount(cp);}
        return false;
    }
    static boolean storable(String value){return value!=null&&value.length()<=MAX_CHARS&&usable(value);}
    static String preview(String value){
        if(!usable(value))return "";
        StringBuilder out=new StringBuilder();boolean space=false;int count=0;
        for(int i=0;i<value.length();){int cp=value.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)){space=out.length()>0;continue;}
            if(count>=36)return out.toString()+"…";
            if(space){out.append(' ');space=false;}out.appendCodePoint(cp);count++;
        }
        return out.toString();
    }
    static String[] prepend(String value,String[] previous){
        ArrayList<String> out=new ArrayList<>();if(storable(value))out.add(value);
        if(previous!=null)for(String item:previous)if(storable(item)&&!out.contains(item)){out.add(item);if(out.size()==MAX_ITEMS)break;}
        return out.toArray(new String[0]);
    }
    /** The current readable copy is runtime-only when too large for persistent history. Never truncate it. */
    static String[] withCurrent(String current,String[] saved){
        ArrayList<String> out=new ArrayList<>();if(usable(current))out.add(current);
        if(saved!=null)for(String item:saved)if(storable(item)&&!out.contains(item)){out.add(item);if(out.size()==MAX_ITEMS)break;}
        return out.toArray(new String[0]);
    }
    /** A captured selection can be used once only while both text and cursor still match. */
    static final class Selection {
        final String original;final int start,end;private boolean consumed;
        Selection(String original,int start,int end){this.original=original;this.start=start;this.end=end;}
        boolean valid(){return original!=null&&start>=0&&end>=0&&start<=original.length()&&end<=original.length();}
        String take(String current,int a,int b,String clip){
            if(consumed || !usable(clip) || !valid() || !original.equals(current) || start!=a || end!=b)return null;
            consumed=true;return original.substring(0,Math.min(start,end))+clip+original.substring(Math.max(start,end));
        }
        int cursor(String clip){return Math.min(start,end)+clip.length();}
    }
    static boolean same(EditorInsertion.Snapshot a,EditorInsertion.Snapshot b){
        return a!=null&&b!=null&&a.start==b.start&&a.end==b.end&&a.before.equals(b.before)&&a.selected.equals(b.selected)&&a.after.equals(b.after);
    }
}
