package app.voiceflow;

public final class EditorInsertionTest {
    static final class Editor implements EditorInsertion.Port {
        String text;int a,b;boolean current=true,unknown,full;
        Editor(String text,int a,int b){this.text=text;this.a=a;this.b=b;}
        public boolean current(){return current;}
        public EditorInsertion.Snapshot read(){if(!current)return null;int lo=Math.min(a,b),hi=Math.max(a,b);return new EditorInsertion.Snapshot(text.substring(full?0:Math.max(0,lo-512),lo),text.substring(lo,hi),text.substring(hi,full?text.length():Math.min(text.length(),hi+512)),unknown?-1:a,unknown?-1:b);}
        public boolean replace(int start,int end,int previousLength,String value){if(start<0){start=Math.min(a,b)-previousLength;end=Math.max(a,b);}text=text.substring(0,start)+value+text.substring(end);a=b=start+value.length();return true;}
    }
    public static void main(String[] args){
        check(EditorInsertion.replacementMode(new EditorInsertion.Snapshot("before","","after",7,7),7,7,0)==0);
        check(EditorInsertion.replacementMode(new EditorInsertion.Snapshot("before","selected","after",16,5),5,16,0)==0);
        check(EditorInsertion.replacementMode(new EditorInsertion.Snapshot("before words","","after",20,20),7,20,13)==1);
        check(EditorInsertion.replacementMode(new EditorInsertion.Snapshot("before words","","after",19,19),7,20,13)==2);
        check(EditorInsertion.replacementMode(new EditorInsertion.Snapshot("before words","","after",-1,-1),-1,-1,13)==1);
        Editor editor=new Editor("Before after",7,7);EditorInsertion range=new EditorInsertion(editor.read());
        check(range.write(editor,"hello"));check(range.write(editor,"hello, world"));equal("Before hello, world after",editor.text);
        check(range.write(editor,"Hello, world."));equal("Before Hello, world. after",editor.text);
        check(range.write(editor,"Hello, world!"));equal("Before Hello, world! after",editor.text);
        check(range.write(editor,"Hello, world"));equal("Before Hello, world after",editor.text);
        editor=new Editor("Before.After",7,7);range=new EditorInsertion(editor.read());check(range.write(editor,"Next."));equal("Before. Next. After",editor.text);
        editor=new Editor("Say () next",5,5);range=new EditorInsertion(editor.read());check(range.write(editor,"hello"));equal("Say (hello) next",editor.text);
        editor=new Editor("Say \"\" next",5,5);range=new EditorInsertion(editor.read());check(range.write(editor,"hello"));equal("Say \"hello\" next",editor.text);
        editor=new Editor("Hello next",5,5);range=new EditorInsertion(editor.read());check(range.write(editor,","));equal("Hello, next",editor.text);
        editor=new Editor("Keep wrong words after",16,5);range=new EditorInsertion(editor.read());check(range.write(editor,"right"));equal("Keep right after",editor.text);check(range.write(editor,"correct"));equal("Keep correct after",editor.text);
        editor=new Editor("আমার কথা",5,5);range=new EditorInsertion(editor.read());check(range.write(editor,"নতুন"));equal("আমার নতুন কথা",editor.text);
        editor=new Editor("Before after",7,7);range=new EditorInsertion(editor.read());check(range.write(editor,"hello"));editor.text="My manual edit";editor.a=editor.b=14;check(!range.write(editor,"ignored"));equal("My manual edit",editor.text);
        editor=new Editor("Before after",7,7);range=new EditorInsertion(editor.read());check(range.write(editor,"hello"));editor.a=editor.b=0;check(!range.write(editor,"ignored"));
        editor=new Editor("Before after",7,7);editor.unknown=true;range=new EditorInsertion(editor.read());check(range.write(editor,"hello"));check(range.write(editor,"hello world"));equal("Before hello world after",editor.text);
        editor=new Editor("prefix ",7,7);range=new EditorInsertion(editor.read());StringBuilder longWords=new StringBuilder();for(int i=0;i<800;i++)longWords.append("word ");check(range.write(editor,longWords.toString()));check(range.write(editor,"shorter"));equal("prefix shorter",editor.text);
        editor.current=false;check(!range.write(editor,"wrong field"));
        final Editor delayed=new Editor("Before after",7,7);final long[] time={0};final String[] queued={null};final int[] sends={0};
        EditorInsertion.Port lag=new EditorInsertion.Port(){public EditorInsertion.Snapshot read(){return delayed.read();}public boolean replace(int a,int b,int previous,String text){sends[0]++;queued[0]=text;return true;}};
        range=new EditorInsertion(lag.read(),()->time[0]);check(range.write(lag,"hello"));check(!range.matches(lag.read()));time[0]=150;
        check(range.compatible(lag.read()));check(range.write(lag,"hello"));check(sends[0]==1);time[0]=1500;check(range.compatible(lag.read()));
        delayed.replace(7,7,0,queued[0]);check(range.matches(lag.read()));check(range.write(lag,"hello"));check(sends[0]==1);
        range=new EditorInsertion(lag.read(),()->time[0]);check(range.write(lag,"next"));time[0]+=EditorInsertion.ACK_MS;check(!range.compatible(lag.read()));check(!range.write(lag,"next"));check(sends[0]==2);
        final Editor moved=new Editor("Before after",7,7);EditorInsertion.Port wait=new EditorInsertion.Port(){public EditorInsertion.Snapshot read(){return moved.read();}public boolean replace(int a,int b,int n,String text){return true;}};
        range=new EditorInsertion(wait.read());check(range.write(wait,"hello"));moved.a=moved.b=0;check(!range.compatible(wait.read()));check(!range.write(wait,"hello"));
        final Editor restoring=new Editor("Before after",7,7);final int[] restoreSends={0};EditorInsertion.Port node=new EditorInsertion.Port(){public EditorInsertion.Snapshot read(){return restoring.read();}public boolean replace(int a,int b,int n,String text){restoreSends[0]++;restoring.replace(a,b,n,text);restoring.a=restoring.b=restoring.text.length();return true;}};
        range=new EditorInsertion(node.read());check(range.write(node,"hello"));check(range.compatible(node.read())&&!range.matches(node.read()));check(range.write(node,"hello")&&restoreSends[0]==1);restoring.a=restoring.b=13;check(range.matches(node.read()));
        StringBuilder prefix=new StringBuilder();for(int i=0;i<800;i++)prefix.append('x');editor=new Editor(prefix+" after",800,800);editor.full=true;range=new EditorInsertion(editor.read());check(range.write(editor,"hello"));check(range.matches(editor.read()));check(range.write(editor,"hello world"));check(range.matches(editor.read()));
        System.out.println("PASS: editor range revisions, sentence/quote/bracket spacing, selected/reversed ranges, Unicode, long text, missing offsets, manual edits, cursor moves, and changed input");
    }
    static void equal(String a,String b){if(!a.equals(b))throw new AssertionError("Expected ["+a+"] got ["+b+"]");}
    static void check(boolean good){if(!good)throw new AssertionError("Unexpected editor result");}
}
