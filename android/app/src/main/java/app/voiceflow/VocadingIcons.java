package app.voiceflow;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** Native assets adapted from the supplied Vocading Forest & Copper concept sheet. */
final class VocadingIcons {
    static Drawable icon(Context context,String name,int color,int size){
        int resource=context.getResources().getIdentifier("ic_vocading_"+name.replace('-','_'),"drawable",context.getPackageName());
        if(resource==0)return null;
        Drawable icon=context.getDrawable(resource).mutate();
        if(color!=0)icon.setTint(color);icon.setBounds(0,0,Ui.dp(context,size),Ui.dp(context,size));return icon;
    }
    static void attach(TextView view,String name,int color){
        if(!AppProduct.isNotes())return;
        view.setCompoundDrawablesRelative(icon(view.getContext(),name,color,22),null,null,null);view.setCompoundDrawablePadding(Ui.dp(view.getContext(),6));
    }
    static void label(TextView view,String label,int color){
        String name=null;
        switch(label){
            case "Record":name="ready";color=0;break;
            case "Play":case "Resume":name="play";color=0;break;
            case "Pause":name="pause";color=0;break;
            case "Finish":case "Stop":name="stop";break;
            case "Share":name="share";break;
            case "Delete":case "Delete permanently":case "Recently deleted":name="delete";break;
            case "Import recordings":name="import";break;
            case "Summary":case "Copy":name="transcript";break;
        }
        if(name!=null)attach(view,name,color);
    }
    private VocadingIcons(){}
}
