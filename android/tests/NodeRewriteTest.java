package app.voiceflow;

public final class NodeRewriteTest {
    static final class Node implements NodeRewrite.Port {
        String value="one two tail";int a=7,b=7,sets,selections;boolean current=true,allowSelection=true,advertised=true,pretendSelection;
        public boolean current(){return current;}
        public EditorInsertion.Snapshot read(){int lo=Math.min(a,b),hi=Math.max(a,b);return new EditorInsertion.Snapshot(value.substring(0,lo),value.substring(lo,hi),value.substring(hi),a,b);}
        public boolean select(int start,int end){selections++;if(!allowSelection)return false;if(!pretendSelection){a=start;b=end;}return true;}
        public boolean text(String next){sets++;value=next;a=b=next.length();return true;}
    }
    static void check(boolean okay){if(!okay)throw new AssertionError();}
    public static void main(String[] ignored){
        Node node=new Node();EditorInsertion.Snapshot source=node.read();
        check(NodeRewrite.dispatch(node,source,"one  tail",4));check(node.sets==1&&node.a==4&&node.value.equals("one  tail")&&node.selections==2);
        node=new Node();node.allowSelection=false;source=node.read();check(!NodeRewrite.dispatch(node,source,"one  tail",4));check(node.sets==0&&node.value.equals("one two tail"));
        node=new Node();node.advertised=false;source=node.read();check(NodeRewrite.dispatch(node,source,"one  tail",4));check(node.sets==1&&node.a==4);
        node=new Node();source=node.read();node.a=node.b=0;check(!NodeRewrite.dispatch(node,source,"wrong",5));check(node.sets==0);
        node=new Node();source=new EditorInsertion.Snapshot("one two",""," tail",-1,-1);check(!NodeRewrite.dispatch(node,source,"one  tail",4));check(node.sets==0);
        node=new Node();node.value="one two";node.a=node.b=7;node.allowSelection=false;source=node.read();check(NodeRewrite.dispatch(node,source,"one ",4));check(node.sets==1&&node.a==4);
        node=new Node();node.current=false;check(!NodeRewrite.dispatch(node,node.read(),"wrong",5));check(node.sets==0);
        System.out.println("PASS: actual selection preflight refuses middle rewrites before text mutation; real support works without advertisements; stale/unknown/changed targets refused; end-of-field fallback remains safe");
    }
}
