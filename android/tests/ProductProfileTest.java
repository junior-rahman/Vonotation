package app.voiceflow;

import java.util.Arrays;

/** Product boundaries, stable restored pages and recording ownership. */
public final class ProductProfileTest {
    private static int checks;
    private static void check(boolean ok,String detail){checks++;if(!ok)throw new AssertionError(detail);}
    public static void main(String[] args){
        check(Arrays.equals(ProductProfile.pages(false,true),new int[]{3,4,5}),"Original has only Dictation, Thoughts and Clipboard");
        check(Arrays.equals(ProductProfile.pages(false,false),new int[]{3,5}),"Thoughts Off hides only Thoughts");
        check(Arrays.equals(ProductProfile.pages(true,true),new int[]{0,1}),"Notes ignores the original Thoughts preference");
        check(Arrays.equals(ProductProfile.pages(true,false),new int[]{0,1}),"Notes has only Voice Notes and Calls");
        for(boolean notes:new boolean[]{false,true}){
            for(int page=-2;page<9;page++){
                boolean expected=notes?(page==0||page==1):(page==3||page==4||page==5);
                check(ProductProfile.supports(notes,page)==expected,"Capability boundary for page "+page);
            }
            for(boolean thoughts:new boolean[]{false,true})for(int saved=-2;saved<9;saved++){
                int restored=ProductProfile.restore(notes,thoughts,saved);boolean visible=false;
                for(int page:ProductProfile.pages(notes,thoughts))visible|=restored==page;
                check(visible,"A saved page from the other app must not reopen a hidden feature");
            }
        }
        check(ProductProfile.recording(false,false,false,true),"Original can capture Thoughts");
        check(!ProductProfile.recording(false,false,false,false),"Original cannot start Voice Notes");
        check(!ProductProfile.recording(false,false,true,false),"Original cannot start Calls");
        check(ProductProfile.recording(true,false,false,false),"Notes can capture Voice Notes");
        check(ProductProfile.recording(true,false,true,false),"Notes can capture Calls");
        check(!ProductProfile.recording(true,false,false,true),"Notes cannot capture Thoughts");
        check(!ProductProfile.recording(false,true,false,false)&&!ProductProfile.recording(true,true,false,false),"Neither app exposes the retired Translation recorder");
        check(!ProductProfile.recording(false,false,true,true)&&!ProductProfile.recording(true,false,true,true),"Conflicting thought/call identities are rejected");
        int[] original=ProductProfile.pages(false,true);original[0]=0;
        check(ProductProfile.pages(false,true)[0]==3,"Page arrays are not shared mutable state");
        System.out.println("PASS: "+checks+" product split boundary checks.");
    }
}
