package app.voiceflow;
public final class NotebookPolicyTest {
    public static void main(String[] args){
        assert NotebookPolicy.matches("meeting FRIDAY","Friday","Team meeting");
        assert NotebookPolicy.matches("বাংলা", "আমার বাংলা নোট");
        assert NotebookPolicy.matches("café", "cafe\u0301");
        assert !NotebookPolicy.matches("Friday missing", "Friday meeting");
        assert NotebookPolicy.matches("", "");
        assert NotebookPolicy.status(false,false,true,false,false,false,false,true,false,true).contains("key");
        assert NotebookPolicy.status(false,false,true,false,false,true,false,true,true,true).contains("partial");
        assert NotebookPolicy.status(true,true,true,false,false,false,false,true,true,true).startsWith("Paused");
        assert NotebookPolicy.status(false,false,false,false,false,false,false,false,true,true).contains("unavailable");
        System.out.println("PASS");
    }
}
